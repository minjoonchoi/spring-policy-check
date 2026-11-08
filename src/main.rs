use spring_policy::{config::Config, engine::analyze};
use std::{env, fs, path::PathBuf, process::ExitCode};
use walkdir::WalkDir;

fn run() -> Result<i32, String> {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.iter().any(|a| a == "--version") {
        println!("spring-policy {}", env!("CARGO_PKG_VERSION"));
        return Ok(0);
    }
    if args.is_empty() || args.iter().any(|a| a == "--help") {
        println!("spring-policy check --config policy.yml --module :app --root src/main/kotlin [--root other] [--report report.json] [--threads N]\nExit 0: no errors; 1: policy errors; 2: configuration/parse/IO failure.\nRust syntax and declared-type analysis; unsupported semantic cases are reported as SKIP.");
        return Ok(0);
    }
    if args[0] != "check" {
        return Err("Expected 'check'; use --help".into());
    }
    let mut config = None;
    let mut module = None;
    let mut roots = vec![];
    let mut report = None;
    let mut threads = None;
    let mut i = 1;
    while i < args.len() {
        let key = &args[i];
        let value = args
            .get(i + 1)
            .ok_or_else(|| format!("Missing value for {key}"))?;
        match key.as_str() {
            "--config" => config = Some(value.clone()),
            "--module" => module = Some(value.clone()),
            "--root" => roots.push(PathBuf::from(value)),
            "--report" => report = Some(PathBuf::from(value)),
            "--threads" => {
                let n = value.parse::<usize>().map_err(|_| "Invalid thread count")?;
                if n == 0 {
                    return Err("threads must be positive".into());
                }
                threads = Some(n);
            }
            _ => return Err(format!("Unknown option: {key}")),
        }
        i += 2;
    }
    let config = config.ok_or("--config is required")?;
    let module = module.ok_or("--module is required")?;
    if roots.is_empty() {
        return Err("At least one --root is required".into());
    }
    if let Some(path) = &report {
        if path.exists() {
            fs::remove_file(path).map_err(|e| e.to_string())?;
        }
    }
    let config = Config::load(&fs::read_to_string(config).map_err(|e| e.to_string())?)?;
    let mut paths = std::collections::BTreeSet::new();
    for root in roots {
        if !root.exists() {
            return Err(format!("Source root does not exist: {}", root.display()));
        }
        for entry in WalkDir::new(root).follow_links(false) {
            let entry = entry.map_err(|e| e.to_string())?;
            if entry.file_type().is_file() && entry.path().extension().is_some_and(|x| x == "kt") {
                paths.insert(entry.path().to_path_buf());
            }
        }
    }
    let inputs = paths
        .into_iter()
        .map(|p| {
            let text = fs::read_to_string(&p).map_err(|e| format!("{}: {e}", p.display()))?;
            Ok((p.to_string_lossy().replace('\\', "/"), module.clone(), text))
        })
        .collect::<Result<Vec<_>, String>>()?;
    let count = threads.unwrap_or_else(|| {
        std::thread::available_parallelism()
            .map_or(1, |n| n.get())
            .min(8)
    });
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(count)
        .build()
        .map_err(|e| e.to_string())?;
    let result = pool.install(|| analyze(&config, inputs))?;
    for d in &result.diagnostics {
        let level = if d.status == "skipped" {
            "SKIP".into()
        } else {
            format!("{:?}", d.severity).to_uppercase()
        };
        println!(
            "{}:{}:{} [{level}] {} {} — {}",
            d.file, d.line, d.column, d.rule, d.code, d.message
        );
        if let Some(s) = &d.suggestion {
            println!("  제안: {s}");
        }
    }
    let errors = result
        .diagnostics
        .iter()
        .filter(|d| d.severity == spring_policy::config::Severity::Error)
        .count();
    let skipped = result
        .diagnostics
        .iter()
        .filter(|d| d.status == "skipped")
        .count();
    println!(
        "{} files; {} applicable rules; {errors} errors; {} diagnostics; {skipped} skipped",
        result.files,
        result.applicable_rules.len(),
        result.diagnostics.len()
    );
    if let Some(path) = &report {
        if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        fs::write(
            path,
            serde_json::to_vec_pretty(&result).map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;
    }
    Ok(result.exit_code())
}
fn main() -> ExitCode {
    match run() {
        Ok(code) => ExitCode::from(code as u8),
        Err(e) => {
            eprintln!("ANALYSIS_ERROR: {e}");
            ExitCode::from(2)
        }
    }
}
