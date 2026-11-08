use crate::syntax::{ancestor, child, children, descendants, is_type, Source};
use std::collections::BTreeMap;
use tree_sitter::Node;

#[derive(Debug, Clone)]
pub struct Field {
    pub typ: String,
    pub column: Option<String>,
}
#[derive(Debug, Clone)]
pub struct Class {
    pub fqn: String,
    pub name: String,
    pub entity: Option<String>,
    pub table: Option<String>,
    pub fields: BTreeMap<String, Field>,
    pub supers: Vec<String>,
    pub repository_entity: Option<String>,
    pub complex_mapping: bool,
}
pub type Index = BTreeMap<String, Class>;
pub fn has_token(n: Node<'_>, text: &str) -> bool {
    (0..n.child_count()).any(|i| n.child(i).is_some_and(|c| c.kind() == text))
}
pub fn resolve(source: &Source, raw: &str, index: &Index) -> Option<String> {
    source.resolve(raw).or_else(|| {
        let simple = raw.trim().split(['<', '?']).next()?;
        let fqn = format!("{}.{}", source.package, simple);
        index.contains_key(&fqn).then_some(fqn)
    })
}
pub fn own_annotations<'a>(s: &Source, n: Node<'a>) -> Vec<(String, Node<'a>)> {
    s.attached_annotations(n)
        .into_iter()
        .filter_map(|a| s.annotation_name(a).map(|f| (f, a)))
        .collect()
}
fn annotation_value(s: &Source, n: Node<'_>, suffix: &str, key: &str) -> Option<String> {
    own_annotations(s, n)
        .into_iter()
        .find(|(name, _)| {
            name == &format!("jakarta.persistence.{suffix}")
                || name == &format!("javax.persistence.{suffix}")
        })
        .and_then(|(_, a)| s.annotation_args(a).get(key).and_then(|v| s.string(*v)))
}
pub fn class_fqn(s: &Source, n: Node<'_>) -> Option<String> {
    s.declaration_name(n)
        .map(|name| format!("{}.{}", s.package, name))
}
pub fn build_index(sources: &[Source]) -> Result<Index, String> {
    let mut index = Index::new();
    for s in sources {
        for n in s.nodes("class_declaration") {
            // The v0.1 project index supports top-level declarations only.
            if ancestor(n, &["class_declaration", "function_declaration"]).is_some() {
                continue;
            }
            let name = s.declaration_name(n).unwrap_or_default();
            let fqn = format!("{}.{}", s.package, name);
            let anns = own_annotations(s, n);
            let entity = anns
                .iter()
                .any(|(a, _)| a == "jakarta.persistence.Entity" || a == "javax.persistence.Entity")
                .then(|| {
                    annotation_value(s, n, "Entity", "name")
                        .filter(|x| !x.is_empty())
                        .unwrap_or(name.clone())
                });
            let table = annotation_value(s, n, "Table", "name");
            let mut fields = BTreeMap::new();
            let mut complex = false;
            for node in descendants(n) {
                let variable = if node.kind() == "class_parameter"
                    && (has_token(node, "val") || has_token(node, "var"))
                {
                    Some(node)
                } else if node.kind() == "property_declaration"
                    && ancestor(node, &["function_declaration"]).is_none()
                {
                    child(node, "variable_declaration")
                } else {
                    None
                };
                if let Some(v) = variable {
                    if ancestor(v, &["class_declaration"]).map(|a| a.id()) != Some(n.id()) {
                        continue;
                    }
                    let Some(field_name) = s.declaration_name(v) else {
                        continue;
                    };
                    let Some(ty) = children(v).into_iter().find(|c| is_type(*c)) else {
                        complex = true;
                        continue;
                    };
                    let typ = s.slice(ty).to_owned();
                    let field_annotations = own_annotations(s, node);
                    if field_annotations
                        .iter()
                        .any(|(a, _)| a.ends_with(".Transient"))
                    {
                        continue;
                    }
                    if field_annotations.iter().any(|(a, _)| {
                        ![
                            "jakarta.persistence.Id",
                            "jakarta.persistence.Column",
                            "jakarta.persistence.GeneratedValue",
                            "javax.persistence.Id",
                            "javax.persistence.Column",
                            "javax.persistence.GeneratedValue",
                        ]
                        .contains(&a.as_str())
                    }) {
                        complex = true;
                    }
                    if ![
                        "String", "Long", "Int", "Boolean", "Double", "Float", "Short", "Byte",
                    ]
                    .contains(&typ.trim_end_matches('?'))
                    {
                        complex = true;
                    }
                    let column = annotation_value(s, node, "Column", "name");
                    fields.insert(field_name, Field { typ, column });
                }
            }
            let mut supers = vec![];
            let mut repository_entity = None;
            if let Some(specs) = child(n, "delegation_specifiers") {
                for spec in children(specs) {
                    if let Some(ty) = descendants(spec)
                        .into_iter()
                        .find(|x| x.kind() == "user_type")
                    {
                        let raw = s.slice(ty);
                        if let Some(fq) = s.resolve(raw) {
                            if [
                                "org.springframework.data.jpa.repository.JpaRepository",
                                "org.springframework.data.repository.CrudRepository",
                                "org.springframework.data.repository.Repository",
                            ]
                            .contains(&fq.as_str())
                            {
                                repository_entity = raw
                                    .split_once('<')
                                    .and_then(|(_, rest)| rest.split([',', '>']).next())
                                    .map(|v| {
                                        s.resolve(v.trim()).unwrap_or_else(|| {
                                            format!("{}.{}", s.package, v.trim())
                                        })
                                    });
                            }
                            supers.push(fq);
                        } else {
                            supers.push(format!(
                                "{}.{}",
                                s.package,
                                raw.split('<').next().unwrap_or(raw)
                            ));
                        }
                    }
                }
            }
            if entity.is_some() && !supers.is_empty() {
                complex = true;
            }
            if anns.iter().any(|(a, _)| {
                a.starts_with("org.hibernate.")
                    || a.ends_with(".Inheritance")
                    || a.ends_with(".SecondaryTable")
                    || a.ends_with(".Convert")
            }) {
                complex = true;
            }
            let class = Class {
                fqn: fqn.clone(),
                name,
                entity,
                table,
                fields,
                supers,
                repository_entity,
                complex_mapping: complex,
            };
            if index.insert(fqn.clone(), class).is_some() {
                return Err(format!("Duplicate class in source index: {fqn}"));
            }
        }
    }
    Ok(index)
}
pub fn has_super(fqn: &str, target: &[String], index: &Index, depth: usize) -> bool {
    if target.iter().any(|t| t == fqn) {
        return true;
    }
    if depth > 16 {
        return false;
    }
    index.get(fqn).is_some_and(|c| {
        c.supers
            .iter()
            .any(|s| has_super(s, target, index, depth + 1))
    })
}

pub fn declared_receiver(s: &Source, at: Node<'_>, name: &str, index: &Index) -> Option<String> {
    let mut candidates = vec![];
    for n in descendants(s.tree.root_node()) {
        if !["parameter", "class_parameter", "variable_declaration"].contains(&n.kind())
            || s.declaration_name(n).as_deref() != Some(name)
        {
            continue;
        }
        let scope = match n.kind() {
            "class_parameter" => ancestor(n, &["class_declaration"]),
            "parameter" => ancestor(n, &["function_declaration"]),
            _ => ancestor(n, &["block", "class_declaration"]),
        }
        .unwrap_or(s.tree.root_node());
        if scope.start_byte() > at.start_byte() || scope.end_byte() < at.end_byte() {
            continue;
        }
        if scope.kind() == "block" && n.start_byte() > at.start_byte() {
            continue;
        }
        let typ = children(n)
            .into_iter()
            .find(|x| is_type(*x))
            .map(|x| s.slice(x).to_owned());
        candidates.push((
            scope.end_byte() - scope.start_byte(),
            std::cmp::Reverse(n.start_byte()),
            typ,
        ));
    }
    candidates.sort_by_key(|c| (c.0, c.1));
    // An inferred local binding shadows an explicitly typed outer binding.
    candidates
        .first()
        .and_then(|x| x.2.as_deref())
        .and_then(|t| resolve(s, t, index))
}

pub fn constant(s: &Source, n: Node<'_>, depth: usize) -> Option<String> {
    if depth > 8 {
        return None;
    }
    if let Some(v) = s.string(n) {
        return Some(v);
    }
    let raw = s.slice(n);
    if raw == "HttpHeaders.AUTHORIZATION"
        && s.imports
            .get("HttpHeaders")
            .is_some_and(|v| v == "org.springframework.http.HttpHeaders")
    {
        return Some("Authorization".into());
    }
    if n.kind() != "identifier" {
        return None;
    }
    for p in s.nodes("property_declaration") {
        if ancestor(p, &["class_declaration", "function_declaration"]).is_some() {
            continue;
        }
        if !child(p, "modifiers")
            .is_some_and(|m| s.slice(m).split_whitespace().any(|v| v == "const"))
        {
            continue;
        }
        if child(p, "variable_declaration")
            .and_then(|v| s.declaration_name(v))
            .as_deref()
            != Some(raw)
        {
            continue;
        }
        return children(p)
            .last()
            .copied()
            .and_then(|v| constant(s, v, depth + 1));
    }
    None
}

pub fn call_parts<'a>(s: &Source, n: Node<'a>) -> Option<(String, String, Vec<Node<'a>>)> {
    let callee = children(n).first().copied()?;
    if callee.kind() != "navigation_expression" {
        return None;
    }
    let parts = children(callee);
    if parts.len() != 2 || parts[0].kind() != "identifier" {
        return None;
    }
    let args = child(n, "value_arguments")
        .map(|v| {
            children(v)
                .into_iter()
                .filter(|a| a.kind() == "value_argument")
                .filter_map(|a| children(a).last().copied())
                .collect()
        })
        .unwrap_or_default();
    Some((s.slice(parts[0]).into(), s.slice(parts[1]).into(), args))
}

pub fn callee_name(s: &Source, n: Node<'_>) -> Option<String> {
    let callee = children(n).first().copied()?;
    if callee.kind() == "identifier" {
        Some(s.slice(callee).into())
    } else if callee.kind() == "navigation_expression" {
        children(callee).last().map(|n| s.slice(*n).into())
    } else {
        None
    }
}
