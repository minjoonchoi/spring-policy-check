# 오픈소스 사용·고지·배포 기준

이 프로젝트의 목표는 기업 내부 도구로 사용하고, 수정한 코드와 바이너리를 배포할 수 있는 검증기를 제공하는 것입니다. 프로젝트 자체는 MIT 라이선스이며, 제3자 코드의 권리와 조건은 각 원본 라이선스를 따릅니다.

회사 고유의 승인·금지 목록은 아직 제공되지 않았습니다. 아래 내용은 저장소의 보수적인 기본 기준이며 회사의 최종 승인으로 표현하지 않습니다. 회사 기준이 추가되면 더 엄격한 조건을 적용하고, 용도와 맞지 않는 의존성은 대체하거나 독립 구현합니다.

## 1. 도입 기준

| 구분 | 기준 |
| --- | --- |
| 사용 범위 | 기업 내부 실행, 상업적 이용, 수정, 소스·바이너리 재배포 조건을 각각 확인 |
| 자동 허용 범위 | `deny.toml`에 명시한 MIT, Apache-2.0, Apache-2.0 WITH LLVM-exception, BSD-2-Clause, Zlib, Unicode-3.0, Unicode-DFS-2016, ICU |
| 의존성 범위 | 직접·간접·build·dev·대상 OS별 Cargo 의존성을 포함. 버전과 registry checksum 고정 |
| 검토 기록 | 이름, 버전, 출처, 선언 SPDX 식, 선택한 조건, 추가 내장 코드의 조건, 고지 원문과 SHA-256 기록 |
| 미등록·불명 | 라이선스 누락, 알 수 없는 식, 미검토 버전·출처·파일 변경은 CI 실패 |
| 제한 조건 | 비상업 전용, 특정 기업·매출·용도 제한, 소스 공개 의무가 제품 배포 방식과 충돌하는 항목은 도입하지 않고 대체 |
| 예외 처리 | 검사 비활성화나 포괄적 allowlist 추가로 통과시키지 않음. 회사가 제공한 구체적인 허용 근거가 있을 때만 범위를 명시해 기준 수정 |

GPL·AGPL·LGPL·MPL은 상업적 사용 자체가 금지된 라이선스라는 뜻이 아닙니다. 이 프로젝트는 배포 조건을 단순하게 유지하기 위해 해당 라이선스만으로 제공되는 **애플리케이션 Cargo 의존성**을 기본 허용 목록에 넣지 않습니다. 명확히 허용된 대안 라이선스가 있는 경우는 그 대안을 선택할 수 있습니다.

`MIT OR Apache-2.0`는 선택한 한 가지 조건을 충족합니다. `(MIT OR Apache-2.0) AND Unicode-3.0`는 MIT 또는 Apache-2.0 중 하나와 Unicode 조건을 모두 충족해야 합니다. 단순히 문자열에 MIT가 포함됐다는 이유로 통과시키지 않으며 SPDX 식은 cargo-deny로 평가합니다. `BSL-1.0`은 Boost Software License이고, `BUSL-1.1`은 Business Source License이므로 혼동하지 않습니다.

## 2. 고지 기준

| 라이선스 | 배포 시 보존·이행할 핵심 조건 |
| --- | --- |
| MIT | 원 저작권 고지와 허가문·면책문 보존 |
| Apache-2.0 | 라이선스 사본, 해당되는 원본 고지, 원본에 있는 NOTICE의 관련 내용 보존. 수정한 원본 파일에는 변경 표시. 특허·상표 조건 유지 |
| BSD-2-Clause | 소스의 저작권·조건·면책문 보존, 바이너리 배포의 문서·동봉 자료에도 해당 고지 포함 |
| Zlib | 출처를 허위로 표시하지 않음. 수정한 원본임을 표시하고 소스 배포에서 원 고지를 유지 |
| Unicode·ICU | 해당 버전의 저작권·허가·면책 고지를 파일 또는 동봉 문서에 보존. 권리자 이름을 무단 홍보에 사용하지 않음 |

저장소 기본 정책으로는 법적 최소 범위보다 단순한 **모든 배포물에 전체 고지 동봉**을 적용합니다. README에 라이브러리 이름이나 링크만 나열하는 것으로 대체하지 않습니다. 선택하지 않은 대안 라이선스 원문도 출처 보존을 위해 함께 유지할 수 있으며, 실제 선택은 목록에 별도로 기록합니다.

- `LICENSE`: 이 프로젝트의 MIT 라이선스.
- `THIRD_PARTY_NOTICES.md`: 버전, 선언·선택 조건, 내장 구성요소, 원문 위치를 보여주는 목록.
- `third_party/dependencies.json`: Cargo 의존성별 검토 스냅샷과 원본 패키지 checksum.
- `third_party/components.json`: Rust 표준 라이브러리, Gradle wrapper, 고정된 CI 도구·Action의 검토 스냅샷.
- `third_party/**`: 원문 그대로 보존한 라이선스·저작권·관련 고지. 체크섬과 원본 패키지의 내용을 비교.

이 프로젝트는 가져온 제3자 소스를 수정하지 않습니다. 수정본을 도입하면 원 고지를 유지하고 변경 파일·내용·버전을 별도 기록해야 합니다. 자동 탐지로 발견하지 못하는 인라인 고지나 새롭게 포함된 데이터도 의존성 업데이트 때 원본을 검토해야 합니다.

## 3. 현재 점검 결과

2026-11-08 기준 직접 의존성 8개와 전체 Cargo 의존성 40개를 검토했습니다. 선언된 조건과 확인한 내장 코드에는 이 프로젝트의 사용·배포 방식에 맞지 않아 대체해야 할 라이브러리가 발견되지 않았습니다.

| 주요 구성요소 | 검토 결과 |
| --- | --- |
| tree-sitter 0.25.10 | MIT. Cargo 배포본에 빠진 최상위 라이선스를 동일 upstream commit에서 보완. 포함된 ICU 헤더의 Unicode·ICU 고지도 보존 |
| tree-sitter-kotlin-ng 1.1.0 | MIT. Cargo 배포본에 빠진 라이선스를 동일 upstream commit에서 보완 |
| serde / serde_json / rayon 등 | 허용된 MIT 대안 선택. 정확한 버전은 의존성 목록에 기록 |
| serde_yaml_ng / unsafe-libyaml | MIT. Rust 변환본에 사용한 원 libyaml의 저작권 고지도 추가 보존 |
| regex-syntax / unicode-ident | crate 선언 외 Unicode 데이터 고지, AND 조건을 함께 반영 |
| foldhash | Zlib. 고지 보존 조건으로 허용 |
| Gradle wrapper 8.10.2 | 저장소에 포함되는 jar의 `META-INF/LICENSE` 추출·보존, wrapper 관련 파일 checksum 고정 |
| Rust 표준 라이브러리 1.99.1 | 배포 바이너리에 연결되는 런타임 고지 문서 동봉. 현재 패키징 대상은 Linux x86_64 GNU |

Rust의 원본 `COPYRIGHT-library.html`은 여러 플랫폼을 포괄합니다. 여기에 나타나는 MPL-2.0 `fortanix-sgx-abi`는 [Rust 1.99.1의 SGX 전용 의존성](https://github.com/rust-lang/rust/blob/1.99.1/library/std/Cargo.toml)이며 현재 배포하는 Linux GNU 대상에 포함되지 않습니다. 원문을 임의로 삭제하지 않고 보존합니다. 다른 대상을 추가하면 그 대상의 런타임을 별도 검토해야 합니다.

Linux 바이너리는 시스템 glibc·libgcc_s에 동적으로 의존하며 이 파일들을 배포 압축 파일에 넣지 않습니다. Rust 컴파일러, JDK, 전체 Gradle 배포판, CI Action의 전체 실행환경도 배포하지 않습니다. 이를 포함하는 컨테이너·설치 패키지는 별도 범위로 목록과 조건을 검토해야 합니다. 도구를 실행한다는 사실과 그 도구를 제품에 포함해 재배포하는 경우를 구분합니다.

## 4. 검증과 배포

개발자 검증 환경은 Python 3.11 이상과 아래 버전의 cargo-deny가 필요합니다. 일반 `policyCheck` 실행에는 필요하지 않습니다.

```bash
cargo install cargo-deny --version 0.20.2 --locked
python3 scripts/license-check.py
./gradlew licenseCheck
python3 -m unittest discover -s tests -p 'test_license_compliance.py' -v
```

`license-check.py`는 cargo-deny의 SPDX 허용 검사를 실행한 뒤, 실제 Cargo graph와 검토 목록·checksum·중첩 고지·CI Action 참조를 비교합니다. 허용 목록 안의 라이선스여도 새 버전이나 미등록 파일은 검토 없이 통과하지 않습니다. `--write`는 검토가 완료된 목록으로 문서만 렌더링하며 의존성을 자동 승인하지 않습니다.

배포는 CI의 `python3 scripts/package-release.py`를 사용합니다. 라이선스 검증을 다시 통과한 후 바이너리, 프로젝트 라이선스, 전체 고지·목록을 한 tar.gz에 넣고 실제 압축 파일의 내용과 실행 권한을 검사합니다. checksum도 함께 제공합니다. `policyCheck`의 Rust 실행 경로에는 이 배포 검사를 추가하지 않습니다.

초기 커밋 `86cd2e4`의 바이너리만 담긴 artifact를 재배포할 때는 이 고지 묶음도 함께 제공해야 합니다. 이후 CI에서 생성하는 고지가 포함된 압축 파일을 사용합니다.

## 5. 변경 절차

1. 필요한 기능과 실제 사용·배포 대상을 확인합니다.
2. 정확한 upstream 버전과 라이선스 원문, 간접 의존성, 내장 코드·데이터를 검토합니다.
3. 조건이 맞지 않으면 기능이 같은 허용 라이브러리를 선택하거나 독립 구현합니다. 코드 복사는 대체 구현으로 보지 않습니다.
4. 고지 원문과 출처·선택 조건·checksum을 갱신합니다. 버전 변경 시 이전 파일의 삭제도 목록과 맞춥니다.
5. `python3 scripts/license-check.py --write`로 목록을 렌더링하고 전체 검증을 통과시킵니다.
6. 실제 배포 압축 파일에 고지가 포함됐는지 검증합니다.

## 공식 근거

- [MIT 원문](https://opensource.org/license/mit)
- [Apache-2.0 원문, 특히 4항](https://www.apache.org/licenses/LICENSE-2.0)
- [Zlib 원문](https://opensource.org/license/zlib)
- [Unicode V3 원문](https://www.unicode.org/license.txt)
- [Unicode-DFS-2016](https://spdx.org/licenses/Unicode-DFS-2016.html), [ICU](https://spdx.org/licenses/ICU.html)
- [cargo-deny 라이선스 설정](https://embarkstudios.github.io/cargo-deny/checks/licenses/cfg.html)
