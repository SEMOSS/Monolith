# Monolith Local Docker Compose

Docker Compose files for running a **locally built** Monolith image, from a single
node up to a two-node cluster. They live in one folder and share the two support
files below, so pick a variant and point `docker compose -f` at it.

## Build the image first

Unlike the SEMOSS examples, these do **not** use the published
`quay.io/semoss/...` image. They run `image: local-monolith`, which you build
yourself from this checkout:

```bash
# from local_docker_testing/ (one level up)
./createLocalDockerScript.sh
```

That builds Semoss + Monolith with Maven and produces the `local-monolith` image
in your local Docker daemon (see [../Dockerfile](../Dockerfile)). Because the
image is local, the compose files intentionally have **no `pull_policy: always`**
- there is nothing to pull, and adding that flag would make Compose try (and
fail) to fetch `local-monolith` from a registry. Rebuild the image whenever you
want to pick up new code.

## Variants

| File | What it stands up | Sync backend | Nodes |
|------|-------------------|--------------|-------|
| [semoss-with-postgres.yml](semoss-with-postgres.yml) | Monolith + PostgreSQL. The simplest local instance, no object storage or clustering. | none | 1 |
| [semoss-with-postgres-minio.yml](semoss-with-postgres-minio.yml) | Monolith + PostgreSQL + MinIO. Single node in cloud-storage mode (`SEMOSS_IS_CLUSTER: 'true'`) using MinIO as the S3-compatible storage provider. | none (storage only) | 1 |
| [semoss-with-postgres-minio-redis.yml](semoss-with-postgres-minio-redis.yml) | Two-node cluster + PostgreSQL + MinIO + Redis (+ RedisInsight UI). Nodes sync via `RedisClusterSynchronizer`. | Redis | 2 |
| [semoss-with-postgres-minio-zk.yml](semoss-with-postgres-minio-zk.yml) | Two-node cluster + PostgreSQL + MinIO + Apache ZooKeeper. Nodes sync via `ClusterSynchronizer`. The ZooKeeper counterpart to the Redis example. | ZooKeeper | 2 |

All four use `image: local-monolith` and the Postgres credentials
`myuser` / `mypassword` (local-dev defaults).

## Shared support files

- [init.sql](init.sql) - runs on first Postgres startup and creates the SEMOSS
  system databases: `semoss_localmaster`, `semoss_security`, `semoss_scheduler`,
  `semoss_themes`, `semoss_prompt`, `semoss_modellogs`, `semoss_usertracking`,
  `semoss_audit`. Used by every variant.
- [init-bucket.sh](init-bucket.sh) - runs on MinIO startup and uses the MinIO
  client (`mc`) to create the `semoss` bucket. Used by every variant except the
  basic one.

## Usage

After building `local-monolith`, from this directory choose one variant with `-f`:

```bash
# basic single node
docker compose -f semoss-with-postgres.yml up            # add -d to detach

# single node with MinIO object storage
docker compose -f semoss-with-postgres-minio.yml up

# two-node cluster with Redis synchronization
docker compose -f semoss-with-postgres-minio-redis.yml up

# two-node cluster with ZooKeeper synchronization
docker compose -f semoss-with-postgres-minio-zk.yml up
```

Common lifecycle commands (append the same `-f <file>`):

```bash
docker compose -f <file> logs -f semoss   # or semoss1 semoss2 for cluster variants
docker compose -f <file> down             # stop, keep data
docker compose -f <file> down -v          # stop and wipe all named volumes
```

> The variants use fixed `container_name`s (`semoss`, `postgres`, `minio`, ...),
> so run one variant at a time. `down` the current one before bringing up another.

## Endpoints by variant

| | basic | with-minio | with-minio-and-redis | with-minio-and-zk |
|--|:--:|:--:|:--:|:--:|
| Monolith node 1 | http://localhost:9090/#/ | http://localhost:9090/#/ | http://localhost:9090/#/ | http://localhost:9090/#/ |
| Monolith node 2 | - | - | http://localhost:9091/#/ | http://localhost:9091/#/ |
| Postgres | localhost:5432 | localhost:5432 | localhost:5432 | localhost:5432 |
| MinIO console | - | http://localhost:9001 | http://localhost:9001 | http://localhost:9001 |
| Sync UI / port | - | - | RedisInsight http://localhost:5540 | ZooKeeper localhost:2181 |

MinIO console login is `minioadmin` / `minioadmin`. In RedisInsight, add a
connection to host `redis` port `6379` (the service name, not `localhost`). For
ZooKeeper, `echo ruok | nc localhost 2181` should return `imok`.

## Notes

- The two cluster variants use YAML anchors (`x-semoss-env`, `x-semoss-service`)
  so both nodes share one config block. `HOST_IP` is deliberately set per node
  (`semoss1:8080`, `semoss2:8080`) so each container registers as a distinct
  cluster member.
- The basic variant mounts named volumes for SEMOSS home
  (`semoss_project`, `semoss_model`, ...); the MinIO variants use the object
  store as the durable store instead and only persist `pgdata` / `minio_data`
  (plus `redis_data` or `zk_data` / `zk_datalog`).
- Native auth is enabled for easy local registration. This is for local/dev use
  only - change the credentials and integrate an external SSO before exposing any
  of this.
- Python is enabled (`NETTY_PYTHON` / `NATIVE_PY_SERVER`); R is off (`R_ON: 'false'`).
