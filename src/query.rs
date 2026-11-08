/// A deliberately closed query grammar. Every input token must be consumed.
/// This is not a full JPQL/SQL parser and never claims equivalence outside its subset.
#[derive(Debug, Clone, PartialEq)]
pub struct Predicate {
    pub property: String,
    pub operator: String,
    pub parameter: String,
}
#[derive(Debug, Clone, PartialEq)]
pub struct Shape {
    pub entity: String,
    pub alias: String,
    pub predicates: Vec<Predicate>,
    pub order: Option<(String, String)>,
}

fn lex(text: &str) -> Result<Vec<String>, &'static str> {
    let c: Vec<char> = text.chars().collect();
    let mut i = 0;
    let mut result = Vec::new();
    while i < c.len() {
        if c[i].is_whitespace() {
            i += 1;
            continue;
        }
        let start = i;
        if c[i].is_ascii_alphabetic() || c[i] == '_' {
            i += 1;
            while i < c.len() && (c[i].is_ascii_alphanumeric() || c[i] == '_') {
                i += 1;
            }
        } else if c[i] == ':' || c[i] == '?' {
            i += 1;
            let value_start = i;
            while i < c.len() && (c[i].is_ascii_alphanumeric() || c[i] == '_') {
                i += 1;
            }
            if i == value_start {
                return Err("UNSUPPORTED_QUERY");
            }
        } else if ".*=<>".contains(c[i]) {
            i += 1;
            if i < c.len() && "<>".contains(c[start]) && c[i] == '=' {
                i += 1;
            }
        } else {
            return Err("UNSUPPORTED_QUERY");
        }
        result.push(c[start..i].iter().collect());
    }
    Ok(result)
}
struct Cursor {
    t: Vec<String>,
    i: usize,
}
impl Cursor {
    fn take(&mut self) -> Result<String, &'static str> {
        let t = self.t.get(self.i).cloned().ok_or("UNSUPPORTED_QUERY")?;
        self.i += 1;
        Ok(t)
    }
    fn expect(&mut self, v: &str) -> Result<(), &'static str> {
        if self.take()?.eq_ignore_ascii_case(v) {
            Ok(())
        } else {
            Err("UNSUPPORTED_QUERY")
        }
    }
    fn peek(&self, v: &str) -> bool {
        self.t
            .get(self.i)
            .is_some_and(|s| s.eq_ignore_ascii_case(v))
    }
    fn ident(&mut self) -> Result<String, &'static str> {
        let s = self.take()?;
        if s.chars()
            .next()
            .is_some_and(|c| c.is_ascii_alphabetic() || c == '_')
            && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        {
            Ok(s)
        } else {
            Err("UNSUPPORTED_QUERY")
        }
    }
    fn property(&mut self, alias: &str) -> Result<String, &'static str> {
        self.expect(alias)?;
        self.expect(".")?;
        self.ident()
    }
}

pub fn parse(text: &str, native: bool) -> Result<Shape, &'static str> {
    let mut c = Cursor {
        t: lex(text)?,
        i: 0,
    };
    if c.peek("delete") || c.peek("update") || c.peek("insert") {
        return Err("BULK_DML");
    }
    c.expect("select")?;
    let selected = c.ident()?;
    if native {
        c.expect(".")?;
        c.expect("*")?;
    }
    c.expect("from")?;
    let entity = c.ident()?;
    let alias = c.ident()?;
    if selected != alias {
        return Err("PROJECTION");
    }
    c.expect("where")?;
    let mut predicates = Vec::new();
    loop {
        let property = c.property(&alias)?;
        let operator = c.take()?;
        if !["=", ">", ">=", "<", "<="].contains(&operator.as_str()) {
            return Err("UNSUPPORTED_OPERATOR");
        }
        let parameter = c.take()?;
        if !(parameter.starts_with(':') || parameter.starts_with('?')) {
            return Err("NON_PARAMETER_OPERAND");
        }
        predicates.push(Predicate {
            property,
            operator,
            parameter,
        });
        if c.peek("and") {
            c.take()?;
        } else {
            break;
        }
    }
    let order = if c.peek("order") {
        c.take()?;
        c.expect("by")?;
        let p = c.property(&alias)?;
        let direction = if c.peek("asc") || c.peek("desc") {
            c.take()?.to_ascii_lowercase()
        } else {
            "asc".into()
        };
        Some((p, direction))
    } else {
        None
    };
    if c.i != c.t.len() {
        return Err("UNSUPPORTED_QUERY");
    }
    Ok(Shape {
        entity,
        alias,
        predicates,
        order,
    })
}

fn upper_first(s: &str) -> String {
    let mut c = s.chars();
    c.next()
        .map(|v| v.to_uppercase().collect::<String>() + c.as_str())
        .unwrap_or_default()
}
impl Shape {
    pub fn method(&self) -> String {
        let parts: Vec<_> = self
            .predicates
            .iter()
            .map(|p| {
                upper_first(&p.property)
                    + match p.operator.as_str() {
                        "=" => "",
                        ">" => "GreaterThan",
                        ">=" => "GreaterThanEqual",
                        "<" => "LessThan",
                        "<=" => "LessThanEqual",
                        _ => unreachable!(),
                    }
            })
            .collect();
        let mut name = "findBy".to_string() + &parts.join("And");
        if let Some((p, d)) = &self.order {
            name += &("OrderBy".to_string() + &upper_first(p) + &upper_first(d));
        }
        name
    }
    pub fn jpql(
        &self,
        entity: &str,
        columns: &std::collections::BTreeMap<String, String>,
    ) -> Result<String, &'static str> {
        let mut clauses = Vec::new();
        for p in &self.predicates {
            let attr = columns.get(&p.property).ok_or("UNMAPPED_COLUMN")?;
            clauses.push(format!(
                "{}.{} {} {}",
                self.alias, attr, p.operator, p.parameter
            ));
        }
        let mut query = format!(
            "select {} from {} {} where {}",
            self.alias,
            entity,
            self.alias,
            clauses.join(" and ")
        );
        if let Some((p, d)) = &self.order {
            let attr = columns.get(p).ok_or("UNMAPPED_COLUMN")?;
            query += &format!(" order by {}.{} {}", self.alias, attr, d);
        }
        Ok(query)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn equality_and_sort() {
        assert_eq!(
            parse(
                "select u from User u where u.email = :email order by u.id desc",
                false
            )
            .unwrap()
            .method(),
            "findByEmailOrderByIdDesc"
        );
    }
    #[test]
    fn comparison() {
        assert_eq!(
            parse(
                "select u from User u where u.age >= ?1 and u.id < ?2",
                false
            )
            .unwrap()
            .method(),
            "findByAgeGreaterThanEqualAndIdLessThan"
        );
    }
    #[test]
    fn never_accept_prefix() {
        assert!(parse(
            "select u from User u where u.id = ?1 union select x from X x",
            false
        )
        .is_err());
    }
    #[test]
    fn bulk_is_distinct() {
        assert_eq!(
            parse("delete from User u where u.id = ?1", false).unwrap_err(),
            "BULK_DML"
        );
    }
    #[test]
    fn quoted_literal_is_not_parameter() {
        assert!(parse("select u from User u where u.email = 'x'", false).is_err());
    }
    #[test]
    fn or_is_not_and() {
        assert!(parse("select u from User u where u.id = ?1 or u.id = ?2", false).is_err());
    }
    #[test]
    fn comment_rejected() {
        assert!(parse("select u from User u where u.id = ?1 -- rest", false).is_err());
    }
    #[test]
    fn native_mapping() {
        let columns = [("user_email".into(), "email".into())].into();
        assert_eq!(
            parse("select u.* from users u where u.user_email = :email", true)
                .unwrap()
                .jpql("User", &columns)
                .unwrap(),
            "select u from User u where u.email = :email"
        );
    }
}
