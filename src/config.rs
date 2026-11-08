use regex::Regex;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "kebab-case")]
pub struct Config {
    pub version: u32,
    #[serde(default)]
    pub exclude_path_patterns: Vec<String>,
    pub rules: Vec<Rule>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "kebab-case")]
pub struct Rule {
    pub id: String,
    pub severity: Severity,
    pub scope: Scope,
    pub check: Check,
    pub message: String,
    pub suggestion: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Severity {
    Error,
    Warning,
    Info,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields, rename_all = "kebab-case")]
pub struct Scope {
    pub modules: Vec<String>,
    pub package_pattern: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "kebab-case", deny_unknown_fields)]
pub enum Check {
    #[serde(rename_all = "kebab-case")]
    Name {
        declaration: DeclarationKind,
        name_pattern: String,
    },
    #[serde(rename_all = "kebab-case")]
    AuthBoundary {
        allowed_package_pattern: String,
        allowed_supertypes: Vec<String>,
        #[serde(default)]
        allowed_types: Vec<String>,
        token_headers: Vec<String>,
        #[serde(default)]
        restricted_calls: Vec<String>,
    },
    #[serde(rename_all = "kebab-case")]
    PreferDerivedQuery {
        max_predicates: usize,
        max_method_length: usize,
    },
    PreferQueryAnnotation,
    PreferJpaOverNative,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum DeclarationKind {
    Class,
    DataClass,
}

impl Config {
    pub fn load(text: &str) -> Result<Self, String> {
        let c: Self = serde_yaml_ng::from_str(text)
            .map_err(|e| format!("Invalid policy configuration: {e}"))?;
        if c.version != 1 {
            return Err(format!("Unsupported policy version: {}", c.version));
        }
        if c.rules.is_empty() {
            return Err("At least one rule is required".into());
        }
        let mut ids = HashSet::new();
        for r in &c.rules {
            if r.id.is_empty() || !ids.insert(&r.id) {
                return Err(format!("Duplicate/empty rule ID: {}", r.id));
            }
            if r.scope.modules.is_empty() {
                return Err(format!("{}: scope.modules must be explicit", r.id));
            }
            Regex::new(&r.scope.package_pattern).map_err(|e| e.to_string())?;
            match &r.check {
                Check::Name { name_pattern, .. } => {
                    Regex::new(name_pattern).map_err(|e| e.to_string())?;
                }
                Check::AuthBoundary {
                    allowed_package_pattern,
                    token_headers,
                    ..
                } => {
                    Regex::new(allowed_package_pattern).map_err(|e| e.to_string())?;
                    if token_headers.is_empty() {
                        return Err("token-headers must not be empty".into());
                    }
                }
                Check::PreferDerivedQuery {
                    max_predicates,
                    max_method_length,
                } => {
                    if *max_predicates == 0 || *max_method_length == 0 {
                        return Err("Query limits must be positive".into());
                    }
                }
                _ => {}
            }
        }
        for p in &c.exclude_path_patterns {
            Regex::new(p).map_err(|e| e.to_string())?;
        }
        Ok(c)
    }
}
