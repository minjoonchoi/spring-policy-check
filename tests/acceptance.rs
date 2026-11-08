use serde::Deserialize;
use spring_policy::{config::Config, engine::analyze};
use std::{fs, path::Path};
#[derive(Deserialize)]
struct Expected {
    module: String,
    rule: String,
    expected: Vec<Match>,
}
#[derive(Deserialize)]
struct Match {
    code: String,
    status: String,
    severity: String,
    suggestion_contains: Option<String>,
}
fn run_case(name: &str) {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let path = root.join("tests/fixtures").join(name);
    let expected: Expected =
        serde_json::from_str(&fs::read_to_string(path.join("expected.json")).unwrap()).unwrap();
    let mut config = Config::load(&fs::read_to_string(root.join("policy.yml")).unwrap()).unwrap();
    config.rules.retain(|r| r.id == expected.rule);
    let mut files = vec![];
    for entry in fs::read_dir(&path).unwrap() {
        let p = entry.unwrap().path();
        if p.extension().is_some_and(|x| x == "kt") {
            files.push((
                p.file_name().unwrap().to_string_lossy().to_string(),
                expected.module.clone(),
                fs::read_to_string(p).unwrap(),
            ));
        }
    }
    files.sort_by(|a, b| a.0.cmp(&b.0));
    let result = analyze(&config, files).unwrap_or_else(|e| panic!("{name}: {e}"));
    assert_eq!(
        result.diagnostics.len(),
        expected.expected.len(),
        "{name}: {:?}",
        result.diagnostics
    );
    for (actual, want) in result.diagnostics.iter().zip(expected.expected) {
        assert_eq!(actual.rule, expected.rule, "{name}");
        assert_eq!(actual.code, want.code, "{name}");
        assert_eq!(actual.status, want.status, "{name}");
        assert_eq!(
            format!("{:?}", actual.severity).to_lowercase(),
            want.severity,
            "{name}"
        );
        assert!(actual.line > 0 && actual.column > 0 && !actual.evidence.is_empty());
        if let Some(hint) = want.suggestion_contains {
            assert!(
                actual.suggestion.as_deref().unwrap_or("").contains(&hint),
                "{name}: {:?}",
                actual.suggestion
            );
        }
        if actual.status == "skipped" {
            assert!(actual.suggestion.is_none());
        }
    }
}
macro_rules! case {
    ($name:ident) => {
        #[test]
        fn $name() {
            run_case(stringify!($name));
        }
    };
}
case!(class_name_bad);
case!(class_name_good);
case!(data_name_bad);
case!(data_name_good);
case!(data_kind_not_class);
case!(class_kind_not_data);
case!(command_class_bad);
case!(command_class_good);
case!(query_class_bad);
case!(query_class_good);
case!(command_data_bad);
case!(command_data_good);
case!(query_data_bad);
case!(query_data_good);
case!(out_of_package);
case!(interface_excluded);
case!(enum_excluded);
case!(local_class_excluded);
case!(comments_not_code);
case!(out_of_module);
case!(auth_header_bad);
case!(auth_header_case_insensitive);
case!(auth_other_header);
case!(auth_dynamic_header);
case!(auth_import_alias);
case!(auth_constant);
case!(auth_spring_header_constant);
case!(auth_request_header_annotation);
case!(auth_resolver_allowed);
case!(auth_resolver_wrong_package);
case!(auth_package_alone_insufficient);
case!(auth_decoder_bad);
case!(auth_same_name_other_type);
case!(auth_shadowed_receiver);
case!(auth_inferred_receiver_skips);
case!(auth_principal_allowed);
case!(auth_helper_call_bad);
case!(jpql_derived);
case!(jpql_order);
case!(jpql_nullable_skip);
case!(jpql_or_skip);
case!(jpql_projection_skip);
case!(jpql_bulk_skip);
case!(jpql_property_unknown);
case!(jpql_alias);
case!(jpql_named_param);
case!(jpql_reused_param);
case!(jpql_extra_param);
case!(jpql_foreign_annotation);
case!(jpql_already_derived);
case!(jpql_const);
case!(jpql_lock_skip);
case!(native_query_candidate);
case!(native_annotation_candidate);
case!(native_column_unknown);
case!(native_table_unknown);
case!(native_dialect_skip);
case!(native_converter_skip);
case!(jpql_not_native);
case!(em_query_candidate);
case!(em_dynamic_skip);
case!(em_hint_skip);
case!(em_missing_binding);
case!(em_criteria_allowed);
case!(em_native_candidate);
case!(em_named_query_allowed);
case!(em_other_type_allowed);
