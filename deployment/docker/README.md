# Docker Images for Deployment

Build these images from the repository root:

```bash
./deployment/docker/publish-multiarch.sh <registry> <tag>
```

Equivalent manual commands:

```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f deployment/docker/server-v2/Dockerfile \
  -t <registry>/chatflow-server-v2:<tag> \
  --push \
  .

docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -f deployment/docker/consumer-v3/Dockerfile \
  -t <registry>/chatflow-consumer-v3:<tag> \
  --push \
  .
```

Then set Terraform vars:

- `server_image = "<registry>/chatflow-server-v2:<tag>"`
- `consumer_image = "<registry>/chatflow-consumer-v3:<tag>"`
- optional `rabbit_image = "rabbitmq:3-management"`

If you use GHCR:
- both packages must be public, or EC2 `docker pull` will fail with `unauthorized`
- the image must include `linux/amd64`, because the current Terraform EC2 instances are `t3.*`

Terraform EC2 bootstrap uses Docker host network mode:
- server container listens on host `${chat_port}` (default `8080`)
- consumer health/metrics on host `${consumer_health_port}` (default `8090`)
- RabbitMQ on host `5672` and `15672`
