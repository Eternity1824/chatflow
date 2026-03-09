# Docker Images for Deployment

Build these images from the repository root:

```bash
docker build -f deployment/docker/server-v2/Dockerfile -t <registry>/chatflow-server-v2:<tag> .
docker build -f deployment/docker/consumer/Dockerfile -t <registry>/chatflow-consumer:<tag> .
```

Push to your registry:

```bash
docker push <registry>/chatflow-server-v2:<tag>
docker push <registry>/chatflow-consumer:<tag>
```

Then set Terraform vars:

- `server_image = "<registry>/chatflow-server-v2:<tag>"`
- `consumer_image = "<registry>/chatflow-consumer:<tag>"`
- optional `rabbit_image = "rabbitmq:3-management"`

Terraform EC2 bootstrap uses Docker host network mode:
- server container listens on host `${chat_port}` (default `8080`)
- consumer health/metrics on host `${consumer_health_port}` (default `8090`)
- RabbitMQ on host `5672` and `15672`
