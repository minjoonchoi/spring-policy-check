# 개발 시 오픈소스 사용 기준

이 문서는 **spring-policy-check를 개발하면서 라이브러리를 선택하고 배포할 때 지킬 기준**입니다.

## 의존성 선택

- 직접·간접 의존성의 정확한 버전과 라이선스 원문을 확인합니다. 라이브러리 안에 포함된 다른 코드·데이터도 확인합니다.
- 기업 내부 실행, 상업적 이용, 수정, 소스·바이너리 재배포 조건을 각각 검토합니다.
- 회사의 사용 조건과 맞지 않으면 허용되는 다른 라이브러리를 선택하거나 독립 구현합니다. 기존 코드를 복사하는 것은 독립 구현으로 보지 않습니다.
- MIT·Apache-2.0 등 조건을 충족하기 쉬운 라이브러리를 우선 고려하되, 이름만 보고 사용 가능하다고 단정하지 않습니다.
- 회사 고유의 승인·금지 목록은 아직 제공되지 않았습니다. 해당 기준이 제공되면 우선 적용합니다.

GPL·AGPL·LGPL·MPL도 상업적 사용 자체가 금지된다는 뜻은 아닙니다. 실제 사용·링크·수정·배포 방식에 따른 의무가 회사의 목적에 맞는지를 확인합니다. 비상업 전용이나 특정 기업·용도 제한이 있는 코드는 특히 확인합니다.

`MIT OR Apache-2.0`는 선택한 한 가지 조건을 충족합니다. `(MIT OR Apache-2.0) AND Unicode-3.0`는 선택한 조건과 Unicode 조건을 함께 충족해야 합니다. `BSL-1.0`(Boost)과 `BUSL-1.1`(Business Source)은 서로 다른 라이선스입니다.

## 원문·고지 보존

| 라이선스 | 확인할 주요 의무 |
| --- | --- |
| MIT | 원 저작권 고지와 허가문·면책문 보존 |
| Apache-2.0 | 라이선스 사본과 관련 원본 고지·NOTICE 보존, 수정한 원본 파일에 변경 표시, 특허·상표 조건 확인 |
| BSD-2-Clause | 소스와 바이너리 동봉 자료에 저작권·조건·면책문 보존 |
| Zlib | 출처를 허위 표시하지 않고 수정본임을 표시, 소스 배포의 고지 유지 |
| Unicode·ICU | 해당 버전의 저작권·허가·면책 고지를 파일 또는 동봉 문서에 보존 |

프로젝트의 `LICENSE`는 자체 코드에 대한 MIT 라이선스이며 제3자 코드의 라이선스를 바꾸지 않습니다. README의 이름이나 링크만으로 원문 고지를 대신하지 않습니다.

- `THIRD_PARTY_NOTICES.md`: 버전, 선언·선택 조건과 원문 위치.
- `third_party/dependencies.json`: 검토한 Cargo 의존성의 출처·버전·checksum과 고지 정보.
- `third_party/components.json`: 관련 빌드 도구·런타임의 검토 기록.
- `third_party/**`: 원문 그대로 보존한 라이선스와 관련 고지.

현재 가져온 제3자 소스를 수정하지 않습니다. 수정본을 도입하면 원 고지를 유지하고 변경 내용을 기록합니다. 의존성이나 버전이 바뀌면 위 기록과 고지를 함께 갱신합니다. 배포 압축 파일에는 자체 라이선스와 제3자 고지를 동봉합니다.

## 현재 검토 결과

2026-11-08 기준 직접 의존성 8개, 전체 Cargo 의존성 40개를 확인했습니다. 확인된 조건하에서는 현재 사용·배포 방식에 맞지 않아 대체해야 할 라이브러리가 발견되지 않았습니다. 회사별 별도 승인 여부를 대신하는 판단은 아닙니다.

| 구성요소 | 확인 내용 |
| --- | --- |
| tree-sitter 0.25.10 | MIT. 같은 upstream commit에서 누락된 최상위 라이선스를 보완하고 내장 ICU 헤더의 고지도 보존 |
| tree-sitter-kotlin-ng 1.1.0 | MIT. 같은 upstream commit의 라이선스 원문 보존 |
| serde / serde_json / rayon 등 | 허용된 MIT 대안 선택 |
| serde_yaml_ng / unsafe-libyaml | MIT. 변환에 사용한 원 libyaml의 저작권 고지도 보존 |
| regex-syntax / unicode-ident | crate 조건과 별개로 Unicode 데이터 고지·AND 조건 반영 |
| foldhash | Zlib. 해당 고지 보존 |
| Gradle wrapper 8.10.2 | jar에 포함된 Apache-2.0 라이선스 보존 |
| Rust 표준 라이브러리 1.99.1 | 현재 Linux x86_64 GNU 바이너리에 연결되는 런타임 고지 동봉 |

Rust의 `COPYRIGHT-library.html`은 여러 플랫폼을 포괄합니다. MPL-2.0 `fortanix-sgx-abi`는 [SGX 전용 의존성](https://github.com/rust-lang/rust/blob/1.99.1/library/std/Cargo.toml)이며 현재 배포하는 Linux GNU 대상에는 포함되지 않습니다. 원문은 보존하며, 다른 배포 대상을 추가할 때 해당 런타임을 확인합니다.

Linux 바이너리가 사용하는 시스템 glibc·libgcc_s, JDK, 전체 Gradle 배포판, Rust 컴파일러, CI 실행환경은 배포 압축 파일에 넣지 않습니다. 이를 포함하는 컨테이너나 설치 패키지를 만들면 그 범위의 라이선스도 검토합니다. 개발 도구를 실행하는 경우와 제품에 포함해 재배포하는 경우를 구분합니다.

초기 커밋 `86cd2e4`의 바이너리만 담긴 artifact를 재배포할 때는 이 고지 묶음도 함께 제공해야 합니다. 현재 CI 배포 압축 파일에는 고지를 포함합니다.

## 공식 근거

- [MIT](https://opensource.org/license/mit), [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0), [Zlib](https://opensource.org/license/zlib)
- [Unicode V3](https://www.unicode.org/license.txt), [Unicode-DFS-2016](https://spdx.org/licenses/Unicode-DFS-2016.html), [ICU](https://spdx.org/licenses/ICU.html)
