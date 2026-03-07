# Deployment Framework (Assignment 2)

This folder contains deployment artifacts for Part 3 (ALB + multi-instance WebSocket servers).

## Planned contents

- `alb-checklist.md`: ALB target group and stickiness configuration.
- `server-env-example.sh`: environment variables for `server-v2`.
- `consumer-env-example.sh`: environment variables for `consumer`.
- startup scripts for EC2 instances.
- `terraform/`: one-shot AWS infrastructure provisioning (ALB + servers + RabbitMQ + consumer).

## Notes

- Health check path is `/health`.
- Internal broadcast endpoint is `/internal/broadcast`.
- Keep `CHATFLOW_INTERNAL_TOKEN` consistent across server and consumer.

## Terraform quick start

Terraform files are in `deployment/terraform`:

1. Copy vars:
   - `cp terraform.tfvars.example terraform.tfvars`
2. Fill required values in `terraform.tfvars`:
   - `admin_cidrs`
   - `chatflow_internal_token`
   - optional `key_name`
3. Run:
   - `terraform init`
   - `terraform plan`
   - `terraform apply`

Outputs include ALB DNS, server IPs, RabbitMQ IP, and consumer IP.
