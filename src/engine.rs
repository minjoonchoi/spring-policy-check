use crate::{config::*, model::*, query, syntax::*};
use rayon::prelude::*;
use regex::Regex;
use serde::Serialize;
use std::collections::{BTreeMap, BTreeSet};
use tree_sitter::Node;

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "kebab-case")]
pub struct Diagnostic {
    pub rule: String,
    pub status: String,
    pub severity: Severity,
    pub code: String,
    pub file: String,
    pub line: usize,
    pub column: usize,
    pub evidence: String,
    pub message: String,
    pub suggestion: Option<String>,
}
#[derive(Debug, Serialize)]
#[serde(rename_all = "kebab-case")]
pub struct Report {
    pub schema_version: u32,
    pub files: usize,
    pub applicable_rules: Vec<String>,
    pub diagnostics: Vec<Diagnostic>,
}
impl Report {
    pub fn exit_code(&self) -> i32 {
        if self
            .diagnostics
            .iter()
            .any(|d| d.severity == Severity::Error)
        {
            1
        } else {
            0
        }
    }
}
struct Compiled<'a> {
    rule: &'a Rule,
    package: Regex,
    name: Option<Regex>,
    allowed_package: Option<Regex>,
}
fn diagnostic(
    s: &Source,
    r: &Rule,
    n: Node<'_>,
    status: &str,
    code: &str,
    suggestion: Option<String>,
) -> Diagnostic {
    let start = s.text[..n.start_byte()].rfind('\n').map_or(0, |i| i + 1);
    Diagnostic {
        rule: r.id.clone(),
        status: status.into(),
        severity: if status == "skipped" {
            Severity::Info
        } else {
            r.severity
        },
        code: code.into(),
        file: s.path.clone(),
        line: n.start_position().row + 1,
        column: s.text[start..n.start_byte()].chars().count() + 1,
        evidence: s.slice(n).chars().take(180).collect(),
        message: r.message.clone(),
        suggestion,
    }
}
fn skipped(s: &Source, r: &Rule, n: Node<'_>, code: &str) -> Diagnostic {
    diagnostic(s, r, n, "skipped", code, None)
}

pub fn analyze(config: &Config, inputs: Vec<(String, String, String)>) -> Result<Report, String> {
    if inputs.is_empty() {
        return Err("No Kotlin source files; analysis was not performed".into());
    }
    let excludes: Vec<_> = config
        .exclude_path_patterns
        .iter()
        .map(|p| Regex::new(p).unwrap())
        .collect();
    let inputs: Vec<_> = inputs
        .into_iter()
        .filter(|(p, _, _)| !excludes.iter().any(|r| r.is_match(p)))
        .collect();
    if inputs.is_empty() {
        return Err("All Kotlin source files were excluded".into());
    }
    let parsed: Vec<Result<Source, String>> = inputs
        .into_par_iter()
        .map(|(p, m, t)| Source::parse(p, m, t))
        .collect();
    let sources: Vec<_> = parsed.into_iter().collect::<Result<_, _>>()?;
    let index = build_index(&sources)?;
    let compiled: Vec<_> = config
        .rules
        .iter()
        .map(|r| Compiled {
            rule: r,
            package: Regex::new(&r.scope.package_pattern).unwrap(),
            name: match &r.check {
                Check::Name { name_pattern, .. } => Some(Regex::new(name_pattern).unwrap()),
                _ => None,
            },
            allowed_package: match &r.check {
                Check::AuthBoundary {
                    allowed_package_pattern,
                    ..
                } => Some(Regex::new(allowed_package_pattern).unwrap()),
                _ => None,
            },
        })
        .collect();
    let mut diagnostics: Vec<_> = sources
        .par_iter()
        .flat_map_iter(|s| {
            compiled
                .iter()
                .filter(|c| {
                    c.rule.scope.modules.contains(&s.module) && c.package.is_match(&s.package)
                })
                .flat_map(|c| inspect(s, c, &index))
                .collect::<Vec<_>>()
        })
        .collect();
    diagnostics.sort_by(|a, b| {
        (&a.file, a.line, a.column, &a.rule, &a.code)
            .cmp(&(&b.file, b.line, b.column, &b.rule, &b.code))
    });
    let applicable_rules = compiled
        .iter()
        .filter(|c| {
            sources
                .iter()
                .any(|s| c.rule.scope.modules.contains(&s.module) && c.package.is_match(&s.package))
        })
        .map(|c| c.rule.id.clone())
        .collect();
    Ok(Report {
        schema_version: 1,
        files: sources.len(),
        applicable_rules,
        diagnostics,
    })
}

fn inspect(s: &Source, c: &Compiled<'_>, index: &Index) -> Vec<Diagnostic> {
    let r = c.rule;
    match &r.check {
        Check::Name { declaration, .. } => s
            .nodes("class_declaration")
            .into_iter()
            .filter_map(|n| {
                if has_token(n, "interface")
                    || has_token(n, "enum")
                    || ancestor(n, &["class_declaration", "function_declaration"]).is_some()
                {
                    return None;
                }
                let modifiers = child(n, "modifiers").map(|m| s.slice(m)).unwrap_or("");
                if modifiers
                    .split_whitespace()
                    .any(|m| m == "annotation" || m == "enum")
                {
                    return None;
                }
                let is_data = modifiers.split_whitespace().any(|m| m == "data");
                if is_data != (*declaration == DeclarationKind::DataClass) {
                    return None;
                }
                let name = s.declaration_name(n)?;
                if c.name.as_ref().unwrap().is_match(&name) {
                    return None;
                }
                Some(diagnostic(
                    s,
                    r,
                    n.child_by_field_name("name").unwrap_or(n),
                    "violation",
                    "NAME_MISMATCH",
                    Some(r.suggestion.clone()),
                ))
            })
            .collect(),
        Check::AuthBoundary {
            allowed_supertypes,
            allowed_types,
            token_headers,
            restricted_calls,
            ..
        } => {
            let allowed = |n: Node<'_>| {
                if !c.allowed_package.as_ref().unwrap().is_match(&s.package) {
                    return false;
                }
                ancestor(n, &["class_declaration"])
                    .and_then(|a| class_fqn(s, a))
                    .is_some_and(|f| {
                        allowed_types.contains(&f) || has_super(&f, allowed_supertypes, index, 0)
                    })
            };
            let mut out = vec![];
            for n in s.nodes("call_expression") {
                let Some((receiver, method, args)) = call_parts(s, n) else {
                    if let Some(method) = callee_name(s, n) {
                        if ["getHeader", "getHeaders"].contains(&method.as_str())
                            || restricted_calls
                                .iter()
                                .any(|c| c.rsplit('#').next() == Some(method.as_str()))
                        {
                            out.push(skipped(s, r, n, "UNRESOLVED_RECEIVER"));
                        }
                    }
                    continue;
                };
                let header_method = method == "getHeader" || method == "getHeaders";
                let restricted_name = restricted_calls
                    .iter()
                    .any(|v| v.rsplit('#').next() == Some(&method));
                if !header_method && !restricted_name {
                    continue;
                }
                let Some(typ) = declared_receiver(s, n, &receiver, index) else {
                    out.push(skipped(s, r, n, "UNRESOLVED_RECEIVER"));
                    continue;
                };
                let header_api = header_method
                    && [
                        "jakarta.servlet.http.HttpServletRequest",
                        "javax.servlet.http.HttpServletRequest",
                    ]
                    .contains(&typ.as_str());
                let restricted = restricted_calls.contains(&format!("{typ}#{method}"));
                if !header_api && !restricted {
                    continue;
                }
                if header_api {
                    let Some(header) = args.first().and_then(|v| constant(s, *v, 0)) else {
                        out.push(skipped(s, r, n, "DYNAMIC_HEADER"));
                        continue;
                    };
                    if !token_headers
                        .iter()
                        .any(|h| h.eq_ignore_ascii_case(&header))
                    {
                        continue;
                    }
                }
                if !allowed(n) {
                    out.push(diagnostic(
                        s,
                        r,
                        n,
                        "violation",
                        "AUTH_OUTSIDE_BOUNDARY",
                        Some(r.suggestion.clone()),
                    ));
                }
            }
            for n in s.nodes("annotation") {
                if s.annotation_name(n).as_deref()
                    != Some("org.springframework.web.bind.annotation.RequestHeader")
                {
                    continue;
                }
                let args = s.annotation_args(n);
                let header = args
                    .get("value")
                    .or_else(|| args.get("name"))
                    .or_else(|| args.get("0"))
                    .and_then(|v| constant(s, *v, 0));
                if header.is_some_and(|h| token_headers.iter().any(|t| t.eq_ignore_ascii_case(&h)))
                    && !allowed(n)
                {
                    out.push(diagnostic(
                        s,
                        r,
                        n,
                        "violation",
                        "AUTH_HEADER_PARAMETER",
                        Some(r.suggestion.clone()),
                    ));
                }
            }
            out
        }
        Check::PreferDerivedQuery {
            max_predicates,
            max_method_length,
        } => query_annotations(s, r, index, false, *max_predicates, *max_method_length),
        Check::PreferJpaOverNative => {
            let mut out = query_annotations(s, r, index, true, usize::MAX, usize::MAX);
            out.extend(entity_manager(s, r, index, true));
            out
        }
        Check::PreferQueryAnnotation => entity_manager(s, r, index, false),
    }
}

fn return_entity<'a>(s: &Source, f: Node<'_>, index: &'a Index) -> Option<&'a Class> {
    let ret = children(f)
        .into_iter()
        .filter(|x| is_type(*x))
        .next_back()?;
    let raw = s.slice(ret).replace(' ', "");
    let (outer, inside) = raw.split_once('<')?;
    if s.resolve(outer).as_deref() != Some("kotlin.collections.List") || !inside.ends_with('>') {
        return None;
    }
    let typ = inside.strip_suffix('>')?;
    if typ.contains(['<', '?']) {
        return None;
    }
    let fqn = resolve(s, typ, index)?;
    index.get(&fqn).filter(|c| c.entity.is_some())
}
#[derive(Debug)]
struct Parameter {
    name: String,
    binding: String,
    typ: String,
}
fn parameters(s: &Source, f: Node<'_>) -> Vec<Parameter> {
    let mut out = vec![];
    let mut explicit = None;
    if let Some(args) = child(f, "function_value_parameters") {
        for n in children(args) {
            if n.kind() == "parameter_modifiers" {
                explicit = descendants(n)
                    .into_iter()
                    .filter(|x| x.kind() == "annotation")
                    .find(|a| {
                        s.annotation_name(*a).as_deref()
                            == Some("org.springframework.data.repository.query.Param")
                    })
                    .and_then(|a| {
                        let args = s.annotation_args(a);
                        args.get("0")
                            .or_else(|| args.get("value"))
                            .and_then(|v| constant(s, *v, 0))
                    });
            } else if n.kind() == "parameter" {
                let name = s.declaration_name(n).unwrap_or_default();
                let typ = children(n)
                    .into_iter()
                    .find(|x| is_type(*x))
                    .map(|t| s.slice(t).to_string())
                    .unwrap_or_default();
                out.push(Parameter {
                    binding: explicit.take().unwrap_or(name.clone()),
                    name,
                    typ,
                });
            }
        }
    }
    out
}
fn derived_candidate(
    s: &Source,
    f: Node<'_>,
    shape: &query::Shape,
    entity: &Class,
    max_predicates: usize,
    max_len: usize,
) -> Result<String, &'static str> {
    if entity.entity.as_deref() != Some(shape.entity.as_str()) {
        return Err("ENTITY_MISMATCH");
    }
    if shape.predicates.len() > max_predicates {
        return Err("COMPLEXITY_LIMIT");
    }
    if shape
        .order
        .as_ref()
        .is_some_and(|(p, _)| !entity.fields.contains_key(p))
    {
        return Err("UNKNOWN_PROPERTY");
    }
    let params = parameters(s, f);
    let mut used = BTreeSet::new();
    let mut ordered = vec![];
    for p in &shape.predicates {
        let field = entity.fields.get(&p.property).ok_or("UNKNOWN_PROPERTY")?;
        // Collections, relations, custom value types/converters need a richer semantic model.
        if ![
            "String", "Long", "Int", "Boolean", "Double", "Float", "Short", "Byte",
        ]
        .contains(&field.typ.trim_end_matches('?'))
        {
            return Err("UNSUPPORTED_PROPERTY_TYPE");
        }
        let param = if let Some(name) = p.parameter.strip_prefix(':') {
            params.iter().find(|a| a.binding == name)
        } else {
            p.parameter
                .strip_prefix('?')
                .and_then(|n| n.parse::<usize>().ok())
                .and_then(|i| i.checked_sub(1))
                .and_then(|i| params.get(i))
        }
        .ok_or("PARAMETER_BINDING")?;
        if !used.insert(&param.name) {
            return Err("REUSED_PARAMETER");
        }
        if param.typ.ends_with('?') {
            return Err("NULL_SEMANTICS");
        }
        if param.typ.trim_end_matches('?') != field.typ.trim_end_matches('?') {
            return Err("PARAMETER_TYPE");
        }
        ordered.push(format!("{}: {}", param.name, param.typ));
    }
    if used.len() != params.len() {
        return Err("EXTRA_PARAMETERS");
    }
    let candidate = shape.method();
    if candidate.len() > max_len {
        return Err("METHOD_LENGTH_LIMIT");
    }
    Ok(format!(
        "fun {candidate}({}): List<{}>",
        ordered.join(", "),
        entity.name
    ))
}

fn query_annotations(
    s: &Source,
    r: &Rule,
    index: &Index,
    native: bool,
    max_predicates: usize,
    max_len: usize,
) -> Vec<Diagnostic> {
    let mut out = vec![];
    for a in s.nodes("annotation") {
        let Some(name) = s.annotation_name(a) else {
            let raw = s
                .slice(a)
                .trim_start_matches('@')
                .split('(')
                .next()
                .unwrap_or("");
            if (raw == "Query" && !native) || (raw == "NativeQuery" && native) {
                out.push(skipped(s, r, a, "UNRESOLVED_ANNOTATION"));
            }
            continue;
        };
        let is_query = name == "org.springframework.data.jpa.repository.Query";
        let is_native = name == "org.springframework.data.jpa.repository.NativeQuery";
        if !is_query && !is_native {
            continue;
        }
        let args = s.annotation_args(a);
        let native_flag = is_native
            || args
                .get("nativeQuery")
                .is_some_and(|v| s.slice(*v) == "true");
        if native_flag != native {
            continue;
        }
        if args
            .get("nativeQuery")
            .is_some_and(|v| !matches!(s.slice(*v), "true" | "false"))
        {
            out.push(skipped(s, r, a, "UNKNOWN_NATIVE_FLAG"));
            continue;
        }
        let Some(f) = ancestor(a, &["function_declaration"]) else {
            continue;
        };
        let Some(owner) = ancestor(f, &["class_declaration"])
            .and_then(|n| class_fqn(s, n))
            .and_then(|name| index.get(&name))
        else {
            out.push(skipped(s, r, a, "REPOSITORY_UNRESOLVED"));
            continue;
        };
        if owner.repository_entity.is_none() {
            out.push(skipped(s, r, a, "REPOSITORY_UNRESOLVED"));
            continue;
        }
        let Some(text) = args
            .get("value")
            .or_else(|| args.get("0"))
            .and_then(|v| constant(s, *v, 0))
        else {
            out.push(skipped(s, r, a, "DYNAMIC_QUERY"));
            continue;
        };
        let shape = match query::parse(&text, native) {
            Ok(v) => v,
            Err(code) => {
                out.push(skipped(s, r, a, code));
                continue;
            }
        };
        // Count/projection/locking metadata is deliberately outside the v0.1 conversion subset.
        if args
            .keys()
            .any(|k| !["0", "value", "nativeQuery"].contains(&k.as_str()))
            || own_annotations(s, f).iter().any(|(n, _)| {
                [
                    "org.springframework.data.jpa.repository.Lock",
                    "org.springframework.data.jpa.repository.QueryHints",
                    "org.springframework.data.jpa.repository.EntityGraph",
                    "org.springframework.data.jpa.repository.Modifying",
                ]
                .contains(&n.as_str())
            })
        {
            out.push(skipped(s, r, a, "QUERY_OPTIONS"));
            continue;
        }
        let Some(entity) = return_entity(s, f, index) else {
            out.push(skipped(s, r, a, "RETURN_TYPE"));
            continue;
        };
        if owner.repository_entity.as_deref() != Some(entity.fqn.as_str()) {
            out.push(skipped(s, r, a, "REPOSITORY_ENTITY_MISMATCH"));
            continue;
        }
        let candidate = if native {
            native_candidate(&shape, entity)
        } else {
            derived_candidate(s, f, &shape, entity, max_predicates, max_len)
        };
        match candidate {
            Ok(candidate) => out.push(diagnostic(
                s,
                r,
                a,
                "suggestion",
                if native {
                    "PREFER_JPA_REVIEW"
                } else {
                    "PREFER_DERIVED"
                },
                Some(format!("{} {candidate}", r.suggestion)),
            )),
            Err(code) => out.push(skipped(s, r, a, code)),
        }
    }
    out
}
fn native_candidate(shape: &query::Shape, entity: &Class) -> Result<String, &'static str> {
    if entity.complex_mapping {
        return Err("COMPLEX_MAPPING");
    }
    if entity.table.as_deref() != Some(shape.entity.as_str()) {
        return Err("TABLE_MAPPING");
    }
    let columns: BTreeMap<_, _> = entity
        .fields
        .iter()
        .filter_map(|(attr, f)| f.column.as_ref().map(|c| (c.clone(), attr.clone())))
        .collect();
    let query = shape.jpql(entity.entity.as_deref().ok_or("ENTITY_MAPPING")?, &columns)?;
    Ok(format!(
        "@Query(\"{query}\") — 런타임 필터·매핑·실행계획 검토 필요; 자동 수정하지 않음"
    ))
}

fn entity_manager(s: &Source, r: &Rule, index: &Index, native: bool) -> Vec<Diagnostic> {
    let mut out = vec![];
    for n in s.nodes("call_expression") {
        let Some((receiver, method, args)) = call_parts(s, n) else {
            if callee_name(s, n).as_deref()
                == Some(if native {
                    "createNativeQuery"
                } else {
                    "createQuery"
                })
            {
                out.push(skipped(s, r, n, "UNRESOLVED_RECEIVER"));
            }
            continue;
        };
        if method
            != if native {
                "createNativeQuery"
            } else {
                "createQuery"
            }
        {
            continue;
        }
        let Some(typ) = declared_receiver(s, n, &receiver, index) else {
            out.push(skipped(s, r, n, "UNRESOLVED_RECEIVER"));
            continue;
        };
        if ![
            "jakarta.persistence.EntityManager",
            "javax.persistence.EntityManager",
        ]
        .contains(&typ.as_str())
        {
            continue;
        }
        let Some(query_arg) = args.first() else {
            continue;
        };
        if !native
            && query_arg.kind() == "identifier"
            && declared_receiver(s, n, s.slice(*query_arg), index)
                .is_some_and(|t| t.ends_with(".CriteriaQuery"))
        {
            continue;
        }
        let Some(text) = constant(s, *query_arg, 0) else {
            out.push(skipped(s, r, n, "DYNAMIC_QUERY"));
            continue;
        };
        let shape = match query::parse(&text, native) {
            Ok(v) => v,
            Err(code) => {
                out.push(skipped(s, r, n, code));
                continue;
            }
        };
        let mut top = n;
        while let Some(p) = top.parent() {
            if matches!(p.kind(), "navigation_expression" | "call_expression") {
                top = p;
            } else {
                break;
            }
        }
        let selectors: Vec<_> = descendants(top)
            .into_iter()
            .filter(|x| x.kind() == "navigation_expression")
            .filter_map(|x| children(x).last().copied().map(|v| s.slice(v)))
            .collect();
        if !selectors.contains(&"resultList")
            || selectors.iter().any(|s| {
                ![
                    "createQuery",
                    "createNativeQuery",
                    "setParameter",
                    "resultList",
                    "class",
                    "java",
                ]
                .contains(s)
            })
        {
            out.push(skipped(s, r, n, "EXECUTION_CHAIN"));
            continue;
        }
        let Some(entity) = args
            .get(1)
            .and_then(|v| s.slice(*v).strip_suffix("::class.java"))
            .and_then(|raw| resolve(s, raw, index))
            .and_then(|fqn| index.get(&fqn))
        else {
            out.push(skipped(s, r, n, "RESULT_MAPPING_REQUIRED"));
            continue;
        };
        let mut bound = BTreeSet::new();
        let mut valid_bindings = true;
        for call in descendants(top)
            .into_iter()
            .filter(|x| x.kind() == "call_expression" && x.id() != n.id())
        {
            let Some(callee) = children(call).first().copied() else {
                continue;
            };
            if children(callee)
                .last()
                .is_some_and(|c| s.slice(*c) == "setParameter")
            {
                let values = child(call, "value_arguments")
                    .map(children)
                    .unwrap_or_default();
                let key = values
                    .first()
                    .and_then(|a| children(*a).last().copied())
                    .and_then(|a| constant(s, a, 0));
                if values.len() != 2 || key.is_none() {
                    valid_bindings = false;
                } else {
                    bound.insert(format!(":{}", key.unwrap()));
                }
            }
        }
        if !valid_bindings
            || bound
                != shape
                    .predicates
                    .iter()
                    .map(|p| p.parameter.clone())
                    .collect()
        {
            out.push(skipped(s, r, n, "PARAMETER_BINDING"));
            continue;
        }
        let candidate = if native {
            native_candidate(&shape, entity)
        } else {
            if entity.entity.as_deref() != Some(shape.entity.as_str()) {
                Err("ENTITY_MISMATCH")
            } else if shape
                .predicates
                .iter()
                .any(|p| !entity.fields.contains_key(&p.property))
            {
                Err("UNKNOWN_PROPERTY")
            } else {
                Ok(format!(
                    "@Query(\"{text}\") — 파라미터·반환 타입·트랜잭션 경계를 보존하세요"
                ))
            }
        };
        match candidate {
            Ok(candidate) => out.push(diagnostic(
                s,
                r,
                n,
                "suggestion",
                if native {
                    "PREFER_JPA_REVIEW"
                } else {
                    "PREFER_QUERY_ANNOTATION"
                },
                Some(format!("{} {candidate}", r.suggestion)),
            )),
            Err(code) => out.push(skipped(s, r, n, code)),
        }
    }
    out
}
