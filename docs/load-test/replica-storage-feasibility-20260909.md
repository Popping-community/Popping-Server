# 저장소 분리 가능성 조사 (2026-09-09)

현재 연결된 로컬 저장 장치만으로 Primary와 Replica의 물리 디스크를 분리할 수 없다. 읽기 전용으로 조사했으며 설정 변경이나 부하 실험은 수행하지 않았다.

## 직접 확인한 구성

- `Get-Disk`와 `Win32_DiskDrive` 모두 물리 디스크 0 하나를 반환: CT500MX500SSD1, 약 500 GB SATA SSD.
- 디스크 0에 C:와 작은 시스템 파티션이 있다. C: 여유 공간은 조회 당시 약 112.65 GB(104.9 GiB).
- `Get-SmbMapping`에 매핑된 네트워크 저장소가 없다. 미연결 장치나 별도 서버의 존재 여부는 이 조사로 알 수 없다.
- WSL 목록에는 실행 중인 WSL 2 `docker-desktop`이 표시된다.
- Docker 데이터 파일: `C:\Users\조용현\AppData\Local\Docker\wsl\disk\docker_data.vhdx`, 조회 당시 파일 크기 약 57.67 GB.
- DB는 각각 `popping-server_mysql-master-data`, `popping-server_mysql-replica-data` local named volume을 `/var/lib/mysql`에 사용한다.
- 양쪽 컨테이너에서 `df -T /var/lib/mysql`은 동일한 `/dev/sde`, ext4를 반환한다.

## 판단

별도 named volume, C:의 별도 폴더, 같은 SSD의 다른 파티션이나 VHDX를 만들어도 물리 장치 공유는 남는다. 별도 VHDX는 가상 저장 경로의 영향을 조사할 수는 있지만 물리 디스크 분리 실험은 아니다.

Docker 데이터 위치 전체를 다른 장치로 옮기더라도 두 DB가 그 안에 함께 있으면 DB 간 장치 공유는 계속된다. 실제 분리 비교에는 두 번째 물리 저장 장치와 DB별 저장 경로 분리가 필요하다. 별도 호스트도 후보지만 네트워크와 실행 환경까지 바뀌므로 저장소만의 효과로 해석할 수 없다.

Windows 폴더 bind mount는 기존 Linux ext4 named volume과 접근 경로가 다르다. Docker 공식 문서도 Linux 파일시스템에서의 bind mount가 Windows 호스트 파일시스템 접근보다 빠르다고 설명한다. 따라서 C: 폴더로 옮기는 것을 저장소 개선으로 가정하지 않는다.

현 상태에서는 물리 분리 비교를 실행할 조건이 확보되지 않았다. 먼저 추가 SSD 또는 별도 테스트 호스트의 가용성을 확인해야 한다. 이후에도 `sync_binlog=1`, `innodb_flush_log_at_trx_commit=1`, 기존 부하와 자원 조건을 유지하고 변경 변수 및 복구 절차를 정해야 한다. RAM 기반 저장소는 동일 내구성 비교로 사용하지 않는다.

기존 sync 지연 관측은 병목 후보의 근거이며 이번 구성 조회만으로 물리 SSD 포화나 복제 지연의 단독 원인을 확정하지 않는다.

## 공식 참고 자료

- Docker Desktop WSL 2 backend: https://docs.docker.com/desktop/features/wsl/
- WSL 2 best practices: https://docs.docker.com/desktop/features/wsl/best-practices/
- WSL disk space and VHD: https://learn.microsoft.com/en-us/windows/wsl/disk-space
