use std::collections::BTreeMap;
use tree_sitter::{Node, Parser, Tree};

pub struct Source {
    pub path: String,
    pub module: String,
    pub text: String,
    pub tree: Tree,
    pub package: String,
    pub imports: BTreeMap<String, String>,
}

pub fn children(n: Node<'_>) -> Vec<Node<'_>> {
    let mut c = n.walk();
    n.named_children(&mut c).collect()
}
pub fn descendants(n: Node<'_>) -> Vec<Node<'_>> {
    let mut result = vec![n];
    for ch in children(n) {
        result.extend(descendants(ch));
    }
    result
}
pub fn ancestor<'a>(mut n: Node<'a>, kinds: &[&str]) -> Option<Node<'a>> {
    while let Some(p) = n.parent() {
        if kinds.contains(&p.kind()) {
            return Some(p);
        }
        n = p;
    }
    None
}
pub fn child<'a>(n: Node<'a>, kind: &str) -> Option<Node<'a>> {
    children(n).into_iter().find(|c| c.kind() == kind)
}
pub fn is_type(n: Node<'_>) -> bool {
    matches!(
        n.kind(),
        "user_type" | "nullable_type" | "function_type" | "parenthesized_type"
    )
}

impl Source {
    pub fn parse(path: String, module: String, text: String) -> Result<Self, String> {
        let mut parser = Parser::new();
        parser
            .set_language(&tree_sitter_kotlin_ng::LANGUAGE.into())
            .map_err(|e| e.to_string())?;
        let tree = parser.parse(&text, None).ok_or("Parser cancelled")?;
        if tree.root_node().has_error() {
            fn problem<'a>(n: Node<'a>, text: &str) -> Option<Node<'a>> {
                // kotlin-ng 1.1 emits this virtual separator for a valid final
                // expression-bodied member before '}'. Never tolerate recovery elsewhere.
                if n.is_missing()
                    && n.kind() == "_class_member_semi"
                    && text[n.start_byte()..].trim_start().starts_with('}')
                {
                    return None;
                }
                if n.is_error() || n.is_missing() {
                    return Some(n);
                }
                for i in 0..n.child_count() {
                    if let Some(e) = problem(n.child(i).unwrap(), text) {
                        return Some(e);
                    }
                }
                None
            }
            if let Some(err) = problem(tree.root_node(), &text) {
                return Err(format!(
                    "{path}:{}:{}: Kotlin parse error; analysis incomplete",
                    err.start_position().row + 1,
                    err.start_position().column + 1
                ));
            }
        }
        let mut source = Self {
            path,
            module,
            text,
            tree,
            package: String::new(),
            imports: BTreeMap::new(),
        };
        for n in children(source.tree.root_node()) {
            let t = source.slice(n).to_owned();
            if n.kind() == "package_header" {
                source.package = t
                    .trim_start_matches("package")
                    .trim()
                    .trim_end_matches(';')
                    .into();
            }
            if n.kind() == "import" || n.kind() == "import_header" {
                let text = t.trim_start_matches("import").trim().trim_end_matches(';');
                let (fqn, alias) = text
                    .split_once(" as ")
                    .map(|(a, b)| (a.trim(), b.trim()))
                    .unwrap_or((text, text.rsplit('.').next().unwrap_or(text)));
                if !fqn.ends_with(".*") {
                    source.imports.insert(alias.into(), fqn.into());
                }
            }
        }
        Ok(source)
    }
    pub fn slice(&self, n: Node<'_>) -> &str {
        &self.text[n.byte_range()]
    }
    pub fn nodes(&self, kind: &str) -> Vec<Node<'_>> {
        descendants(self.tree.root_node())
            .into_iter()
            .filter(|n| n.kind() == kind)
            .collect()
    }
    pub fn declaration_name(&self, n: Node<'_>) -> Option<String> {
        n.child_by_field_name("name")
            .or_else(|| child(n, "identifier"))
            .map(|i| self.slice(i).trim_matches('`').into())
    }
    pub fn resolve(&self, raw: &str) -> Option<String> {
        let raw = raw.trim().split(['<', '?', '(']).next()?.trim();
        if raw.contains('.') {
            return Some(raw.into());
        }
        // A same-file declaration shadows names from wildcard imports; explicit collisions are invalid Kotlin.
        if self
            .nodes("class_declaration")
            .iter()
            .any(|n| self.declaration_name(*n).as_deref() == Some(raw))
        {
            return Some(format!("{}.{}", self.package, raw));
        }
        self.imports.get(raw).cloned().or_else(|| match raw {
            "String" | "Int" | "Long" | "Boolean" | "Double" | "Float" | "Short" | "Byte" => {
                Some(format!("kotlin.{raw}"))
            }
            "List" | "Set" | "Collection" => Some(format!("kotlin.collections.{raw}")),
            _ => None,
        })
    }
    pub fn annotation_name(&self, n: Node<'_>) -> Option<String> {
        let raw = self.slice(n).trim_start_matches('@');
        let before = raw.split('(').next()?.trim();
        let name = before.split(':').next_back()?.trim();
        self.resolve(name)
    }
    pub fn annotation_args<'a>(&self, n: Node<'a>) -> BTreeMap<String, Node<'a>> {
        let mut args = BTreeMap::new();
        if let Some(values) = descendants(n)
            .into_iter()
            .find(|x| x.kind() == "value_arguments")
        {
            for (i, arg) in children(values)
                .into_iter()
                .filter(|x| x.kind() == "value_argument")
                .enumerate()
            {
                let kids = children(arg);
                if self.slice(arg).contains('=') && kids.len() >= 2 {
                    args.insert(self.slice(kids[0]).to_string(), *kids.last().unwrap());
                } else if let Some(v) = kids.last() {
                    args.insert(i.to_string(), *v);
                }
            }
        }
        args
    }
    pub fn string(&self, n: Node<'_>) -> Option<String> {
        if !matches!(n.kind(), "string_literal" | "multiline_string_literal") {
            return None;
        }
        if descendants(n)
            .iter()
            .any(|x| x.kind().contains("interpolation"))
        {
            return None;
        }
        let t = self.slice(n);
        if t.starts_with("\"\"\"") {
            return t
                .strip_prefix("\"\"\"")?
                .strip_suffix("\"\"\"")
                .map(str::to_owned);
        }
        serde_json::from_str(t).ok()
    }
    pub fn attached_annotations<'a>(&self, n: Node<'a>) -> Vec<Node<'a>> {
        child(n, "modifiers")
            .map(|m| {
                descendants(m)
                    .into_iter()
                    .filter(|a| a.kind() == "annotation")
                    .collect()
            })
            .unwrap_or_default()
    }
}
