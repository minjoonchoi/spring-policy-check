fn main() {
    let path = std::env::args().nth(1).unwrap();
    let src = std::fs::read_to_string(path).unwrap();
    let mut p = tree_sitter::Parser::new();
    p.set_language(&tree_sitter_kotlin_ng::LANGUAGE.into())
        .unwrap();
    let t = p.parse(src, None).unwrap();
    println!("{}", t.root_node().to_sexp());
}
