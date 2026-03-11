variable "aws_region" {
  description = "AWS region to deploy resources."
  type        = string
  default     = "us-west-2"
}

variable "name_prefix" {
  description = "Prefix used for all resource names/tags."
  type        = string
  default     = "chatflow"
}

variable "environment" {
  description = "Environment label for tags."
  type        = string
  default     = "dev"
}

variable "key_name" {
  description = "Optional EC2 key pair name for SSH."
  type        = string
  default     = ""
}

variable "allowed_public_cidrs" {
  description = "CIDR blocks allowed to access ALB."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "admin_cidrs" {
  description = "CIDR blocks allowed for SSH and instance-local admin endpoints."
  type        = list(string)
  default     = []
}

variable "associate_public_ip" {
  description = "Whether EC2 instances receive public IPs."
  type        = bool
  default     = true
}

variable "server_count" {
  description = "Number of WebSocket server instances (assignment scenarios: 1/2/4)."
  type        = number
  default     = 4
  validation {
    condition     = var.server_count >= 1 && var.server_count <= 8
    error_message = "server_count must be between 1 and 8."
  }
}

variable "consumer_count" {
  description = "Number of consumer instances (recommended: align with server_count for room sharding)."
  type        = number
  default     = 1
  validation {
    condition     = var.consumer_count >= 1 && var.consumer_count <= 20
    error_message = "consumer_count must be between 1 and 20."
  }
}

variable "server_instance_type" {
  description = "EC2 instance type for ChatFlow server nodes."
  type        = string
  default     = "t3.micro"
}

variable "consumer_instance_type" {
  description = "EC2 instance type for consumer node."
  type        = string
  default     = "t3.small"
}

variable "rabbit_instance_type" {
  description = "EC2 instance type for RabbitMQ node."
  type        = string
  default     = "t3.small"
}

variable "root_volume_size_gb" {
  description = "Root EBS volume size for instances."
  type        = number
  default     = 30
}

variable "alb_idle_timeout_seconds" {
  description = "ALB idle timeout in seconds (should be > 60 for WebSocket)."
  type        = number
  default     = 120
}

variable "alb_sticky_cookie_seconds" {
  description = "ALB lb_cookie stickiness duration."
  type        = number
  default     = 3600
}

variable "chat_port" {
  description = "Server WebSocket/HTTP port."
  type        = number
  default     = 8080
}

variable "server_grpc_port" {
  description = "Server internal gRPC broadcast port."
  type        = number
  default     = 9090
}

variable "consumer_health_port" {
  description = "Consumer health/metrics endpoint port."
  type        = number
  default     = 8090
}

variable "rabbit_port" {
  description = "RabbitMQ AMQP port."
  type        = number
  default     = 5672
}

variable "rabbit_management_port" {
  description = "RabbitMQ management UI port."
  type        = number
  default     = 15672
}

variable "chatflow_internal_token" {
  description = "Shared token for consumer -> server internal broadcast endpoint."
  type        = string
  default     = "change-me"
  sensitive   = true
}

variable "rabbit_username" {
  description = "RabbitMQ username."
  type        = string
  default     = "guest"
}

variable "rabbit_password" {
  description = "RabbitMQ password."
  type        = string
  default     = "guest"
  sensitive   = true
}

variable "rabbit_vhost" {
  description = "RabbitMQ virtual host."
  type        = string
  default     = "/"
}

variable "rabbit_exchange" {
  description = "RabbitMQ exchange name for chat."
  type        = string
  default     = "chat.exchange"
}

variable "room_start" {
  description = "Start room id."
  type        = number
  default     = 1
}

variable "room_end" {
  description = "End room id."
  type        = number
  default     = 20
}

variable "queue_message_ttl_ms" {
  description = "Queue message TTL in milliseconds."
  type        = number
  default     = 60000
}

variable "queue_max_length" {
  description = "Queue max length."
  type        = number
  default     = 10000
}

variable "consumer_threads" {
  description = "Consumer thread count."
  type        = number
  default     = 20
}

variable "consumer_prefetch" {
  description = "Consumer prefetch count."
  type        = number
  default     = 100
}

variable "consumer_room_max_inflight" {
  description = "Max in-flight broadcasts per room on each consumer instance."
  type        = number
  default     = 8
}

variable "consumer_global_max_inflight" {
  description = "Global max in-flight broadcasts on each consumer instance."
  type        = number
  default     = 500
}

variable "consumer_max_retries" {
  description = "Max retry count for failed broadcasts."
  type        = number
  default     = 3
}

variable "consumer_retry_backoff_base_ms" {
  description = "Base backoff for consumer retry (ms)."
  type        = number
  default     = 25
}

variable "consumer_retry_backoff_max_ms" {
  description = "Max backoff for consumer retry (ms)."
  type        = number
  default     = 2000
}

variable "consumer_dedup_max_entries" {
  description = "In-memory dedup max entries."
  type        = number
  default     = 200000
}

variable "consumer_dedup_ttl_ms" {
  description = "In-memory dedup TTL (ms)."
  type        = number
  default     = 120000
}

variable "consumer_broadcast_mode" {
  description = "Internal broadcast target mode for consumer: grpc (host:port) or http (http://host:port)."
  type        = string
  default     = "grpc"
  validation {
    condition     = contains(["grpc", "http"], var.consumer_broadcast_mode)
    error_message = "consumer_broadcast_mode must be either 'grpc' or 'http'."
  }
}

variable "server_jar_url" {
  description = "Optional URL to server-v2 fat jar. If empty, startup service is not installed."
  type        = string
  default     = ""
}

variable "consumer_jar_url" {
  description = "Optional URL to consumer fat jar. If empty, startup service is not installed."
  type        = string
  default     = ""
}

variable "rabbit_image" {
  description = "RabbitMQ container image."
  type        = string
  default     = "rabbitmq:3-management"
}

variable "server_image" {
  description = "Container image for server-v2. If non-empty, user_data runs Docker container."
  type        = string
  default     = ""
}

variable "consumer_image" {
  description = "Container image for consumer. If non-empty, user_data runs Docker container."
  type        = string
  default     = ""
}
