#!/usr/bin/env python3
"""Synthetic source benchmark. Does not claim performance on a real production repository."""
import argparse, json, os, platform, statistics, subprocess, tempfile, time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--binary', default='target/release/spring-policy')
parser.add_argument('--output', default='verification/benchmark.json')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
binary = (root / args.binary).resolve()

def measure(command, repetitions):
    subprocess.run(command, cwd=root, check=True, stdout=subprocess.DEVNULL)
    samples = []
    for _ in range(repetitions):
        start = time.perf_counter()
        subprocess.run(command, cwd=root, check=True, stdout=subprocess.DEVNULL)
        samples.append(round((time.perf_counter()-start)*1000, 3))
    return {'samples_ms': samples, 'median_ms': round(statistics.median(samples), 3), 'min_ms': min(samples), 'max_ms': max(samples)}

prefix = [str(binary), 'check', '--config', 'policy.yml', '--module', ':sample']
small = measure(prefix + ['--root', 'samples/warnings', '--root', 'samples/common'], 10)
with tempfile.TemporaryDirectory(prefix='spring-policy-bench-') as temp:
    directory = Path(temp)
    (directory/'User.kt').write_text((root/'samples/common/User.kt').read_text())
    for i in range(1000):
        if i < 500:
            text = f'package com.example.api.controller\nclass Sample{i}Controller\n'
        else:
            text = (root/'samples/warnings/UserRepository.kt').read_text().replace('UserRepository',f'Repository{i}')
        (directory/f'Sample{i}.kt').write_text(text)
    large = measure(prefix + ['--root', str(directory)], 5)
result = {
    'platform': platform.platform(), 'cpu_count': os.cpu_count(),
    'binary_bytes': binary.stat().st_size,
    'method': 'Release binary, new process per sample, one warm-up; includes source reads/parsing/index/rules/console formatting; stdout discarded. No internal cache. Warm OS filesystem cache. Excludes Gradle and Rust build.',
    'small_3_files': small, 'synthetic_1001_files': large,
    'limitations': 'Synthetic Kotlin subset and shared container; not a production-repository or detekt comparison.'
}
output=root/args.output; output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False,indent=2))
