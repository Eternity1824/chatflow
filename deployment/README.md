# Deployment Framework (Assignment 2)

This folder contains deployment artifacts for Part 3 (ALB + multi-instance WebSocket servers).

## Planned contents

- `alb-checklist.md`: ALB target group and stickiness configuration.
- `server-env-example.sh`: environment variables for `server-v2`.
- `consumer-env-example.sh`: environment variables for `consumer`.
- startup scripts for EC2 instances.

## Notes

- Health check path is `/health`.
- Internal broadcast endpoint is `/internal/broadcast`.
- Keep `CHATFLOW_INTERNAL_TOKEN` consistent across server and consumer.
