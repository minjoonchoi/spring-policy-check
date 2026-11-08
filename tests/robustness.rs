use spring_policy::{config::Config, engine::analyze, syntax::Source};
use std::{fs, path::PathBuf, process::Command};
fn config() -> String {
    fs::read_to_string(PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("policy.yml")).unwrap()
}
#[test]
fn invalid_version_fails() {
    assert!(Config::load(&config().replacen("version: 1", "version: 9", 1)).is_err());
}
#[test]
fn unknown_config_key_fails() {
    assert!(Config::load(&(config() + "\nmisspelled: true\n")).is_err());
}
#[test]
fn bad_regex_fails() {
    assert!(Config::load(&config().replacen("^[A-Z][A-Za-z0-9]*Controller$", "[", 1)).is_err());
}
#[test]
fn empty_rules_fail() {
    assert!(Config::load("version: 1\nrules: []").is_err());
}
#[test]
fn invalid_kotlin_fails() {
    assert!(Source::parse(
        "Broken.kt".into(),
        ":sample".into(),
        "class { fun ( ".into()
    )
    .is_err());
}
#[test]
fn empty_input_fails() {
    assert!(analyze(&Config::load(&config()).unwrap(), vec![]).is_err());
}
#[test]
fn source_order_is_deterministic() {
    let c = Config::load(&config()).unwrap();
    let a = (
        "A.kt".into(),
        ":sample".into(),
        "package com.example.api.controller\nclass WrongA\n".into(),
    );
    let b = (
        "B.kt".into(),
        ":sample".into(),
        "package com.example.api.controller\nclass WrongB\n".into(),
    );
    assert_eq!(
        analyze(&c, vec![a.clone(), b.clone()]).unwrap().diagnostics,
        analyze(&c, vec![b, a]).unwrap().diagnostics
    );
}
#[test]
fn generated_sources_excluded() {
    let c = Config::load(&config()).unwrap();
    let r = analyze(
        &c,
        vec![
            (
                "generated/Wrong.kt".into(),
                ":sample".into(),
                "invalid !!".into(),
            ),
            (
                "Good.kt".into(),
                ":sample".into(),
                "package com.example.api.controller\nclass GoodController\n".into(),
            ),
        ],
    )
    .unwrap();
    assert_eq!(r.files, 1);
    assert!(r.diagnostics.is_empty());
}
#[test]
fn duplicate_class_is_analysis_error() {
    let c = Config::load(&config()).unwrap();
    assert!(analyze(
        &c,
        vec![
            ("A.kt".into(), ":sample".into(), "package a\nclass X".into()),
            ("B.kt".into(), ":sample".into(), "package a\nclass X".into())
        ]
    )
    .is_err());
}
fn cli(group: &str) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_spring-policy"))
        .current_dir(env!("CARGO_MANIFEST_DIR"))
        .args([
            "check",
            "--config",
            "policy.yml",
            "--module",
            ":sample",
            "--root",
            &format!("samples/{group}"),
            "--root",
            "samples/common",
        ])
        .output()
        .unwrap()
}
#[test]
fn cli_valid_exits_zero() {
    let o = cli("valid");
    assert_eq!(o.status.code(), Some(0), "{:?}", o);
}
#[test]
fn cli_warnings_exit_zero() {
    let o = cli("warnings");
    assert_eq!(o.status.code(), Some(0));
    assert!(String::from_utf8_lossy(&o.stdout).contains("PREFER_DERIVED"));
}
#[test]
fn cli_errors_exit_one() {
    assert_eq!(cli("violations").status.code(), Some(1));
}
#[test]
fn cli_missing_root_exits_two() {
    assert_eq!(cli("not-present").status.code(), Some(2));
}
#[test]
fn cli_rejects_unknown_options() {
    let o = Command::new(env!("CARGO_BIN_EXE_spring-policy"))
        .args(["check", "--wat", "true"])
        .output()
        .unwrap();
    assert_eq!(o.status.code(), Some(2));
}
#[test]
fn serial_and_parallel_reports_match() {
    let root = env!("CARGO_MANIFEST_DIR");
    let run = |n: &str| {
        Command::new(env!("CARGO_BIN_EXE_spring-policy"))
            .current_dir(root)
            .args([
                "check",
                "--config",
                "policy.yml",
                "--module",
                ":sample",
                "--root",
                "samples/violations",
                "--root",
                "samples/common",
                "--threads",
                n,
            ])
            .output()
            .unwrap()
            .stdout
    };
    assert_eq!(run("1"), run("4"));
}
