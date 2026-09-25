# 논리 백업 오프사이트 사본 — S3 버킷 · 인스턴스 역할

DB 노드의 논리 백업(`pg-dump.sh`가 `BACKUP_S3_URI`로 올리는 암호화된 덤프)을 받는 버킷과, 노드가 그 버킷에 쓰는 권한을 정의한다. JSON은 주석을 달 수 없어 쓰임과 근거를 여기에 적는다.

| 값 | |
| --- | --- |
| 계정 | `890742606734` |
| 리전 | `ap-northeast-2`(서울) |
| 버킷 | `rental-backup-890742606734` — 논리 백업은 `logical/` 아래 |
| 역할 · 인스턴스 프로필 | `rental-db-backup` |

## 파일

| 파일 | 쓰임 | 근거 |
| --- | --- | --- |
| `role-trust.json` | 역할 신뢰 정책 — EC2만 이 역할을 맡는다 | 서버 운영 기반 설계서 6.1 — 서버가 S3를 쓰는 권한은 키 파일이 아니라 인스턴스 역할로 준다 |
| `role-policy.json` | 역할 권한 — `logical/` 아래 `s3:PutObject`만. 읽기 · 목록 · 삭제가 없다 | 서버 운영 기반 설계서 6.1 「필요한 서비스로 좁힌다」 · 3.3 「역할 권한은 필요한 버킷 · 동작으로 좁힌다」. 노드가 털려도 오프사이트 사본을 읽거나 지우지 못한다. 복원 때 내려받기는 운영자 자격 증명으로 한다 |
| `bucket-policy.json` | TLS가 아닌 요청(`aws:SecureTransport` = false)을 버킷 · 객체 모두 거부 | 백업은 노드를 떠나기 전에 이미 암호화되지만 전송 구간도 평문을 허용하지 않는다 |
| `lifecycle.json` | `logical/` 아래 객체를 28일 뒤 만료, 끝나지 않은 멀티파트 업로드는 1일 뒤 정리(역할이 `AbortMultipartUpload`를 갖지 않아 노드가 스스로 치우지 못한다) | 인프라 기술 스택 정의서 3장 — 4주 보존 |

서버 측 암호화 요구 조건은 역할 정책에 넣지 않는다 — 버킷 기본 암호화(SSE-S3)가 모든 객체에 건다. 버전 관리는 켜지 않는다(기본값이 꺼짐이라 명령이 없다).

## 적용 순서

운영자 PC에서 이 폴더를 작업 디렉터리로 두고 실행한다(`file://`가 상대 경로다). 반영은 운영 작업이다 — 운영 절차서의 해당 절을 따른다.

**1. 버킷 생성**

```sh
aws s3api create-bucket --bucket rental-backup-890742606734 --region ap-northeast-2 \
  --create-bucket-configuration LocationConstraint=ap-northeast-2
```

**2. 퍼블릭 액세스 전면 차단**

```sh
aws s3api put-public-access-block --bucket rental-backup-890742606734 \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
```

**3. 기본 암호화 SSE-S3**

```sh
aws s3api put-bucket-encryption --bucket rental-backup-890742606734 \
  --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
```

**4. 버킷 정책**

```sh
aws s3api put-bucket-policy --bucket rental-backup-890742606734 --policy file://bucket-policy.json
```

**5. 수명 주기**

```sh
aws s3api put-bucket-lifecycle-configuration --bucket rental-backup-890742606734 \
  --lifecycle-configuration file://lifecycle.json
```

**6. 역할 · 인스턴스 프로필**

```sh
aws iam create-role --role-name rental-db-backup --assume-role-policy-document file://role-trust.json
aws iam put-role-policy --role-name rental-db-backup --policy-name rental-db-backup-logical-put \
  --policy-document file://role-policy.json
aws iam create-instance-profile --instance-profile-name rental-db-backup
aws iam add-role-to-instance-profile --instance-profile-name rental-db-backup --role-name rental-db-backup
```

**7. DB-01 · DB-02에 연결** — 인스턴스 ID는 노드마다 넣는다

```sh
aws ec2 associate-iam-instance-profile --region ap-northeast-2 \
  --instance-id <인스턴스 ID> --iam-instance-profile Name=rental-db-backup
```

## 확인

```sh
aws s3api get-public-access-block --bucket rental-backup-890742606734
aws s3api get-bucket-encryption --bucket rental-backup-890742606734
aws s3api get-bucket-policy --bucket rental-backup-890742606734
aws s3api get-bucket-lifecycle-configuration --bucket rental-backup-890742606734
aws ec2 describe-iam-instance-profile-associations --region ap-northeast-2
```

노드에서 `aws s3 ls s3://rental-backup-890742606734/logical/`와 객체 삭제가 **거부**되고 `logical/` 아래 업로드만 되는 것이 역할 정책대로 적용된 상태다.
