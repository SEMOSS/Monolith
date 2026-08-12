# Monolith Local Docker Compose - FIPS

FIPS 140-3 variants of the stacks in [../local-docker-compose](../local-docker-compose).
Identical in every respect except:

- `image: local-monolith-fips` instead of `local-monolith`
- Compose project names are prefixed `local-monolith-fips-`, so a FIPS stack and a
  normal stack can run side by side without sharing containers, volumes or networks
- `init.sql` and `init-bucket.sh` are referenced from `../local-docker-compose/`
  rather than duplicated

## Build the image first

These run `image: local-monolith-fips`, which is **not** the image
`createLocalDockerScript.sh` produces. Build it with the FIPS script:

```bash
# from local-docker-testing/ (one level up)
./createLocalFipsDockerScript.sh
```

That builds Monolith with `-P dev,fips` (BouncyCastle excluded from `WEB-INF/lib`),
then layers the validated provider onto the base image via
[../Dockerfile.fips](../Dockerfile.fips). As with the non-FIPS files, the image is
local, so there is no `pull_policy: always`.

## Variants

Same four stacks as the non-FIPS folder:

| File | What it stands up | Nodes |
|------|-------------------|-------|
| [semoss-with-postgres.yml](semoss-with-postgres.yml) | Monolith + PostgreSQL | 1 |
| [semoss-with-postgres-minio.yml](semoss-with-postgres-minio.yml) | Monolith + PostgreSQL + MinIO | 1 |
| [semoss-with-postgres-minio-zk.yml](semoss-with-postgres-minio-zk.yml) | Two nodes, ZooKeeper sync | 2 |
| [semoss-with-postgres-minio-redis.yml](semoss-with-postgres-minio-redis.yml) | Two nodes, Redis sync | 2 |

```bash
docker compose -f semoss-with-postgres.yml up
```

See [../local-docker-compose/README.md](../local-docker-compose/README.md) for what
each stack contains, ports, and credentials. Everything there applies here.

## Passwords must be at least 14 characters

These files use longer secrets than the non-FIPS ones (`mylocalfipspassword`,
`miniolocalfipssecret`) and that is not cosmetic.

PostgreSQL authenticates with SCRAM-SHA-256, which runs the password through
PBKDF2. SP 800-132 sets a 112-bit floor on the password input, and BC-FIPS
enforces it in approved-only mode. 112 bits is 14 ASCII characters. A shorter
password fails before a connection is ever attempted:

```
org.bouncycastle.crypto.fips.FipsUnapprovedOperationError: password must be at least 112 bits
  at org.postgresql.shaded.com.ongres.scram.common.CryptoUtil.hi
  at org.postgresql.core.v3.ScramAuthenticator.handleAuthenticationSASLContinue
```

That error reads like a database problem but is not - Postgres never sees a bad
credential, the JDBC driver refuses to compute the hash. Any FIPS deployment
needs DB and object-store credentials of 14+ characters.

Changing `POSTGRES_PASSWORD` only takes effect at `initdb` time, so if a stack
already came up with the old password you must drop its volumes:

```bash
docker compose -f semoss-with-postgres.yml down -v
```

## What to expect on startup

These containers run with `-Dorg.bouncycastle.fips.approved_only=true`, so the JVM
refuses non-approved algorithms rather than silently downgrading. Expect
`NoSuchAlgorithmException` on first boot until the remaining call sites are
migrated - that failure surfacing is the point of running these.

Confirm the provider stack is actually active:

```bash
docker compose -f semoss-with-postgres.yml exec semoss \
  bash -c 'grep "^security.provider" $JAVA_HOME/conf/security/java.security'
```

Expect BCFIPS first, BCJSSE second, SUN third, and nothing else.

## Keeping these in sync

These files are copies, not overrides. When you change something in
`../local-docker-compose/`, mirror it here. To regenerate all four from the
originals:

```bash
cd .. && for f in local-docker-compose/*.yml; do
  sed -e 's|image: local-monolith$|image: local-monolith-fips|' \
      -e 's|^name: local-monolith-|name: local-monolith-fips-|' \
      -e 's|- \./init\.sql:|- ../local-docker-compose/init.sql:|' \
      -e 's|- \./init-bucket\.sh:|- ../local-docker-compose/init-bucket.sh:|' \
      "$f" > "local-docker-compose-fips/$(basename "$f")"
done
```
