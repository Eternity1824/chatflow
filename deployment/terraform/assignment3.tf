# ============================================================================
# Assignment 3 -- Persistence & CDC Infrastructure
#
# This file adds:
#   - DynamoDB canonical table (messages_by_id) + projection tables
#   - SQS Dead-Letter Queue for consumer-v3
#   - Lambda CDC projector
#   - ElastiCache Redis for analytics (opt-in via enable_elasticache)
#
# All resources are additive -- nothing in this file modifies existing
# Assignment 2 resources (EC2, ALB, RabbitMQ, security groups).
# ============================================================================

# -- Variables (Assignment 3 specific) ----------------------------------------

variable "enable_persistence" {
  description = "Set to true to create Assignment 3 persistence resources (DynamoDB, SQS, Lambda)."
  type        = bool
  default     = false
}

variable "dynamo_billing_mode" {
  description = "DynamoDB billing mode: PAY_PER_REQUEST or PROVISIONED."
  type        = string
  default     = "PAY_PER_REQUEST"
}

variable "dynamo_stream_enabled" {
  description = "Enable DynamoDB Streams on the canonical table (required for CDC Lambda)."
  type        = bool
  default     = true
}

variable "sqs_dlq_message_retention_seconds" {
  description = "How long SQS DLQ retains messages (default 14 days)."
  type        = number
  default     = 1209600
}

variable "consumer_v3_batch_size" {
  description = "Batch size for consumer-v3 canonical writes."
  type        = number
  default     = 1000
}

variable "consumer_v3_flush_interval_ms" {
  description = "Flush interval for consumer-v3 canonical writes."
  type        = number
  default     = 500
}

variable "consumer_v3_semaphore_permits" {
  description = "Maximum in-flight DynamoDB write tasks for consumer-v3."
  type        = number
  default     = 64
}

variable "consumer_v3_cb_enabled" {
  description = "Enable circuit breaker in consumer-v3."
  type        = bool
  default     = true
}

variable "consumer_v3_cb_failure_threshold" {
  description = "Consecutive failing batches before consumer-v3 circuit opens."
  type        = number
  default     = 5
}

variable "consumer_v3_cb_open_ms" {
  description = "Circuit breaker open duration for consumer-v3."
  type        = number
  default     = 30000
}

variable "server_v3_projection_health_threshold_ms" {
  description = "Projection lag threshold used by server-v2 metrics API."
  type        = number
  default     = 5000
}

variable "lambda_runtime" {
  description = "Lambda runtime for the CDC projector."
  type        = string
  default     = "java21"
}

variable "lambda_memory_mb" {
  description = "Lambda memory allocation in MB."
  type        = number
  default     = 512
}

variable "lambda_timeout_seconds" {
  description = "Lambda timeout in seconds."
  type        = number
  default     = 60
}

variable "lambda_cdc_jar_s3_bucket" {
  description = "S3 bucket holding the CDC Lambda JAR. Leave empty to skip Lambda creation."
  type        = string
  default     = ""
}

variable "lambda_cdc_jar_s3_key" {
  description = "S3 key for the CDC Lambda fat JAR."
  type        = string
  default     = "chatflow/projection-lambda-all.jar"
}

variable "lambda_cdc_jar_local_path" {
  description = "Optional local path to the CDC Lambda fat JAR. If set, Terraform uploads this file directly instead of using S3."
  type        = string
  default     = ""
}

variable "existing_server_instance_profile_name" {
  description = "Existing EC2 instance profile name for server-v2. Set this in restricted lab accounts to reuse a pre-created profile instead of creating a new IAM role/profile."
  type        = string
  default     = ""
}

variable "existing_consumer_instance_profile_name" {
  description = "Existing EC2 instance profile name for consumer-v3. Set this in restricted lab accounts to reuse a pre-created profile instead of creating a new IAM role/profile."
  type        = string
  default     = ""
}

variable "existing_lambda_role_arn" {
  description = "Existing Lambda execution role ARN. Set this in restricted lab accounts to reuse a pre-created role instead of creating a new IAM role."
  type        = string
  default     = ""
}

variable "lambda_cdc_parallelization_factor" {
  description = "Parallelization factor for Lambda A (CDC projector) DynamoDB stream mapping (1-10). Higher values create more concurrent invocations per shard."
  type        = number
  default     = 1
}

variable "lambda_analytics_batch_size" {
  description = "SQS batch size for Lambda B (Redis analytics consumer). Larger batches reduce Redis round-trips."
  type        = number
  default     = 100
}

variable "lambda_analytics_max_concurrency" {
  description = "Maximum concurrent executions for Lambda B SQS consumer. 0 = no limit. Keep low to avoid overwhelming a single-node Redis."
  type        = number
  default     = 5
}

variable "sqs_analytics_visibility_timeout_seconds" {
  description = "SQS analytics queue visibility timeout. Should exceed lambda_timeout_seconds."
  type        = number
  default     = 70
}

variable "sqs_analytics_message_retention_seconds" {
  description = "How long the analytics SQS queue retains unprocessed messages."
  type        = number
  default     = 86400
}

variable "elasticache_node_type" {
  description = "ElastiCache node type for the Redis analytics cluster."
  type        = string
  default     = "cache.t3.micro"
}

variable "elasticache_num_nodes" {
  description = "Number of ElastiCache nodes (1 = no replication)."
  type        = number
  default     = 1
}

variable "elasticache_engine_version" {
  description = "Redis engine version."
  type        = string
  default     = "7.1"
}

variable "enable_elasticache" {
  description = "Set to true to create the Redis ElastiCache cluster for analytics."
  type        = bool
  default     = false
}

variable "elasticache_subnet_ids" {
  description = "Subnet IDs for the ElastiCache subnet group. Required when enable_elasticache = true."
  type        = list(string)
  default     = []
}

# -- Locals --------------------------------------------------------------------

locals {
  # Reuse global name_prefix / tags from main.tf
  dynamo_canonical_table    = "${var.name_prefix}-messages-by-id"
  dynamo_room_messages      = "${var.name_prefix}-room-messages"
  dynamo_user_messages      = "${var.name_prefix}-user-messages"
  dynamo_user_rooms         = "${var.name_prefix}-user-rooms"
  sqs_dlq_name              = "${var.name_prefix}-consumer-v3-dlq"
  sqs_analytics_queue_name  = "${var.name_prefix}-cdc-analytics-queue"
  lambda_cdc_name           = "${var.name_prefix}-cdc-projector"
  lambda_analytics_name     = "${var.name_prefix}-redis-analytics"
  elasticache_cluster_id    = "${var.name_prefix}-redis-analytics"
  persistence_subnet_ids    = length(var.elasticache_subnet_ids) > 0 ? var.elasticache_subnet_ids : local.subnet_ids

  # Lambda A (CDC projector): no longer needs VPC -- it writes to DynamoDB and
  # SQS, both reachable without VPC placement.
  # Lambda B (Redis analytics): needs VPC only when ElastiCache is in-VPC.
  lambda_analytics_uses_vpc = var.enable_persistence && var.enable_elasticache

  # Redis endpoint passed to Lambda B only (empty string if ElastiCache is disabled)
  redis_endpoint = (var.enable_persistence && var.enable_elasticache) ? (
    "${aws_elasticache_cluster.redis_analytics[0].cache_nodes[0].address}:${aws_elasticache_cluster.redis_analytics[0].cache_nodes[0].port}"
  ) : ""

  lambda_uses_local_file = var.lambda_cdc_jar_local_path != ""

  # Whether to create Lambda resources (both Lambda A and Lambda B share this gate)
  create_lambda = var.enable_persistence && (var.lambda_cdc_jar_s3_bucket != "" || local.lambda_uses_local_file)

  create_consumer_v3_iam = var.enable_persistence && var.existing_consumer_instance_profile_name == ""
  create_server_v2_iam   = var.enable_persistence && var.existing_server_instance_profile_name == ""
  create_lambda_iam      = local.create_lambda && var.existing_lambda_role_arn == ""

  resolved_consumer_instance_profile_name = var.enable_persistence ? (
    var.existing_consumer_instance_profile_name != ""
    ? var.existing_consumer_instance_profile_name
    : aws_iam_instance_profile.consumer_v3[0].name
  ) : null

  resolved_server_instance_profile_name = var.enable_persistence ? (
    var.existing_server_instance_profile_name != ""
    ? var.existing_server_instance_profile_name
    : aws_iam_instance_profile.server_v2[0].name
  ) : null

  # Both Lambda A and Lambda B share the same execution role
  resolved_lambda_role_arn = local.create_lambda ? (
    var.existing_lambda_role_arn != ""
    ? var.existing_lambda_role_arn
    : aws_iam_role.cdc_lambda[0].arn
  ) : null
}

# -- DynamoDB -- canonical table (messages_by_id) -------------------------------

resource "aws_dynamodb_table" "messages_by_id" {
  count = var.enable_persistence ? 1 : 0

  name             = local.dynamo_canonical_table
  billing_mode     = var.dynamo_billing_mode
  hash_key         = "messageId"
  stream_enabled   = var.dynamo_stream_enabled
  stream_view_type = var.dynamo_stream_enabled ? "NEW_IMAGE" : null

  attribute {
    name = "messageId"
    type = "S"
  }

  tags = merge(local.tags, { Name = local.dynamo_canonical_table, Assignment = "3" })
}

# -- DynamoDB -- room_messages projection ---------------------------------------
# PK: pk  = "roomId#yyyyMMdd"   (daily partition per room)
# SK: sk  = "eventTsMs#messageId"  (time-ordered, unique per message)

resource "aws_dynamodb_table" "room_messages" {
  count = var.enable_persistence ? 1 : 0

  name         = local.dynamo_room_messages
  billing_mode = var.dynamo_billing_mode
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  tags = merge(local.tags, { Name = local.dynamo_room_messages, Assignment = "3" })
}

# -- DynamoDB -- user_messages projection ---------------------------------------
# PK: pk  = "userId#yyyyMMdd"
# SK: sk  = "eventTsMs#messageId"

resource "aws_dynamodb_table" "user_messages" {
  count = var.enable_persistence ? 1 : 0

  name         = local.dynamo_user_messages
  billing_mode = var.dynamo_billing_mode
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  tags = merge(local.tags, { Name = local.dynamo_user_messages, Assignment = "3" })
}

# -- DynamoDB -- user_rooms projection ------------------------------------------
# PK: userId  (which rooms has this user participated in)
# SK: roomId
# Attribute: lastActivityTs -- updated by CDC Lambda via conditional UpdateItem

resource "aws_dynamodb_table" "user_rooms" {
  count = var.enable_persistence ? 1 : 0

  name         = local.dynamo_user_rooms
  billing_mode = var.dynamo_billing_mode
  hash_key     = "userId"
  range_key    = "roomId"

  attribute {
    name = "userId"
    type = "S"
  }

  attribute {
    name = "roomId"
    type = "S"
  }

  tags = merge(local.tags, { Name = local.dynamo_user_rooms, Assignment = "3" })
}

# -- SQS Dead-Letter Queue (consumer-v3) --------------------------------------

resource "aws_sqs_queue" "consumer_v3_dlq" {
  count = var.enable_persistence ? 1 : 0

  name                      = local.sqs_dlq_name
  message_retention_seconds = var.sqs_dlq_message_retention_seconds

  tags = merge(local.tags, { Name = local.sqs_dlq_name, Assignment = "3" })
}

# -- SQS Analytics Queue (Lambda A -> Lambda B) --------------------------------
# Lambda A (CDC projector) sends AnalyticsEvent JSON messages here after a
# successful DynamoDB projection write.  Lambda B (Redis analytics) polls this
# queue and records analytics in Redis via a single Lua batch call.

resource "aws_sqs_queue" "cdc_analytics_queue" {
  count = local.create_lambda ? 1 : 0

  name                       = local.sqs_analytics_queue_name
  # Visibility timeout must exceed Lambda B's execution timeout so that a
  # message is not redelivered while Lambda B is still processing it.
  visibility_timeout_seconds = var.sqs_analytics_visibility_timeout_seconds
  message_retention_seconds  = var.sqs_analytics_message_retention_seconds

  tags = merge(local.tags, { Name = local.sqs_analytics_queue_name, Assignment = "3" })
}

# -- IAM role for consumer-v3 EC2 instances ------------------------------------

resource "aws_iam_role" "consumer_v3" {
  count = local.create_consumer_v3_iam ? 1 : 0

  name = "${var.name_prefix}-consumer-v3-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = merge(local.tags, { Assignment = "3" })
}

resource "aws_iam_role_policy" "consumer_v3_dynamo_sqs" {
  count = local.create_consumer_v3_iam ? 1 : 0

  name = "${var.name_prefix}-consumer-v3-dynamo-sqs"
  role = aws_iam_role.consumer_v3[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DynamoWrite"
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:GetItem",
          "dynamodb:DescribeTable"
        ]
        Resource = [aws_dynamodb_table.messages_by_id[0].arn]
      },
      {
        Sid      = "SqsDlqSend"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage", "sqs:GetQueueAttributes"]
        Resource = [aws_sqs_queue.consumer_v3_dlq[0].arn]
      }
    ]
  })
}

resource "aws_iam_instance_profile" "consumer_v3" {
  count = local.create_consumer_v3_iam ? 1 : 0

  name = "${var.name_prefix}-consumer-v3-profile"
  role = aws_iam_role.consumer_v3[0].name
}

# -- IAM role for server-v2 query API ------------------------------------------

resource "aws_iam_role" "server_v2" {
  count = local.create_server_v2_iam ? 1 : 0

  name = "${var.name_prefix}-server-v2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = merge(local.tags, { Assignment = "3" })
}

resource "aws_iam_role_policy" "server_v2_query" {
  count = local.create_server_v2_iam ? 1 : 0

  name = "${var.name_prefix}-server-v2-query"
  role = aws_iam_role.server_v2[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ProjectionTableRead"
        Effect = "Allow"
        Action = [
          "dynamodb:Query",
          "dynamodb:GetItem",
          "dynamodb:DescribeTable"
        ]
        Resource = [
          aws_dynamodb_table.room_messages[0].arn,
          aws_dynamodb_table.user_messages[0].arn,
          aws_dynamodb_table.user_rooms[0].arn,
        ]
      }
    ]
  })
}

resource "aws_iam_instance_profile" "server_v2" {
  count = local.create_server_v2_iam ? 1 : 0

  name = "${var.name_prefix}-server-v2-profile"
  role = aws_iam_role.server_v2[0].name
}

# -- IAM role for CDC Lambda ---------------------------------------------------
#
# IMPORTANT -- existing_lambda_role_arn users (lab / restricted accounts)
# -----------------------------------------------------------------------
# When existing_lambda_role_arn is set, Terraform skips creating the role and
# all inline policies below.  The supplied role MUST already have:
#
#   Lambda A (cdc_projector):
#     dynamodb:GetRecords, GetShardIterator, DescribeStream, ListStreams
#       on the canonical table stream ARN
#     dynamodb:PutItem, UpdateItem, GetItem, DescribeTable
#       on room_messages, user_messages, user_rooms table ARNs
#     sqs:SendMessage
#       on the analytics SQS queue ARN  <-- NEW in this deployment
#     logs:CreateLogGroup, CreateLogStream, PutLogEvents
#       (or attach AWSLambdaBasicExecutionRole)
#
#   Lambda B (redis_analytics):
#     sqs:ReceiveMessage, DeleteMessage, ChangeMessageVisibility, GetQueueAttributes
#       on the analytics SQS queue ARN  <-- NEW in this deployment
#     logs:CreateLogGroup, CreateLogStream, PutLogEvents
#     ec2:CreateNetworkInterface, DescribeNetworkInterfaces, DeleteNetworkInterface
#       (only when enable_elasticache = true; or attach AWSLambdaVPCAccessExecutionRole)
#
# If the role lacks sqs:SendMessage / sqs:ReceiveMessage on the new queue,
# Lambda A will receive AccessDenied on every batch and Lambda B will never
# trigger.  The symptom is the analytics queue depth growing unbounded.
#
# Lab note: LabRole typically has broad SQS permissions, but verify with:
#   aws iam simulate-principal-policy \
#     --policy-source-arn <existing_lambda_role_arn> \
#     --action-names sqs:SendMessage sqs:ReceiveMessage sqs:DeleteMessage \
#     --resource-arns <sqs_analytics_queue_arn>

resource "aws_iam_role" "cdc_lambda" {
  count = local.create_lambda_iam ? 1 : 0

  name = "${var.name_prefix}-cdc-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = merge(local.tags, { Assignment = "3" })
}

resource "aws_iam_role_policy_attachment" "cdc_lambda_basic" {
  count      = local.create_lambda_iam ? 1 : 0
  role       = aws_iam_role.cdc_lambda[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "cdc_lambda_vpc_access" {
  count      = (local.create_lambda_iam && var.enable_elasticache) ? 1 : 0
  role       = aws_iam_role.cdc_lambda[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

# Lambda A needs: DynamoDB Streams read + projection table writes + SQS send
# Lambda B needs: SQS receive (managed by event source mapping) -- no explicit policy
#   needed for SQS receive since the event source mapping uses the execution role.
# Both lambdas share this role; the union of permissions is defined here.
resource "aws_iam_role_policy" "cdc_lambda_projections" {
  count = local.create_lambda_iam ? 1 : 0

  name = "${var.name_prefix}-cdc-lambda-projections"
  role = aws_iam_role.cdc_lambda[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ProjectionTableWrite"
        Effect = "Allow"
        Action = [
          "dynamodb:PutItem",
          "dynamodb:UpdateItem",
          "dynamodb:GetItem",
          "dynamodb:DescribeTable"
        ]
        Resource = [
          aws_dynamodb_table.room_messages[0].arn,
          aws_dynamodb_table.user_messages[0].arn,
          aws_dynamodb_table.user_rooms[0].arn,
        ]
      },
      {
        Sid    = "CanonicalStreamRead"
        Effect = "Allow"
        Action = [
          "dynamodb:GetRecords",
          "dynamodb:GetShardIterator",
          "dynamodb:DescribeStream",
          "dynamodb:ListStreams",
        ]
        Resource = [aws_dynamodb_table.messages_by_id[0].stream_arn]
      },
      {
        # Lambda A sends analytics events; Lambda B's SQS polling permissions
        # come from the event source mapping but explicit receive/delete lets
        # the same role be used for both without managed-policy attachment.
        Sid    = "SqsAnalyticsQueue"
        Effect = "Allow"
        Action = [
          "sqs:SendMessage",
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility",
        ]
        Resource = [aws_sqs_queue.cdc_analytics_queue[0].arn]
      }
    ]
  })
}

# -- Lambda A: CDC Projector ----------------------------------------------------
# Reads DynamoDB Streams, writes projections, then publishes AnalyticsEvent
# payloads to SQS for Lambda B.  No VPC needed -- DynamoDB and SQS are
# reachable over the internet from a non-VPC Lambda.

resource "aws_lambda_function" "cdc_projector" {
  count = local.create_lambda ? 1 : 0

  function_name    = local.lambda_cdc_name
  role             = local.resolved_lambda_role_arn
  runtime          = var.lambda_runtime
  handler          = "com.chatflow.cdc.CdcProjectorHandler::handleRequest"
  memory_size      = var.lambda_memory_mb
  timeout          = var.lambda_timeout_seconds
  filename         = local.lambda_uses_local_file ? var.lambda_cdc_jar_local_path : null
  source_code_hash = local.lambda_uses_local_file ? filebase64sha256(var.lambda_cdc_jar_local_path) : null
  s3_bucket        = local.lambda_uses_local_file ? null : var.lambda_cdc_jar_s3_bucket
  s3_key           = local.lambda_uses_local_file ? null : var.lambda_cdc_jar_s3_key

  environment {
    variables = {
      DYNAMO_REGION              = data.aws_region.current.name
      DYNAMO_TABLE_ROOM_MESSAGES = local.dynamo_room_messages
      DYNAMO_TABLE_USER_MESSAGES = local.dynamo_user_messages
      DYNAMO_TABLE_USER_ROOMS    = local.dynamo_user_rooms
      # SQS queue where AnalyticsEvent payloads are sent for Lambda B
      SQS_ANALYTICS_QUEUE_URL   = aws_sqs_queue.cdc_analytics_queue[0].url
      # REDIS_ENDPOINT intentionally omitted -- Lambda A does not touch Redis
    }
  }

  # No vpc_config: Lambda A accesses DynamoDB and SQS without VPC placement.

  tags = merge(local.tags, { Assignment = "3" })
}

resource "aws_vpc_endpoint" "dynamodb_gateway" {
  count = local.lambda_analytics_uses_vpc ? 1 : 0

  vpc_id            = data.aws_vpc.default.id
  service_name      = "com.amazonaws.${data.aws_region.current.name}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [data.aws_vpc.default.main_route_table_id]

  tags = merge(local.tags, { Name = "${var.name_prefix}-dynamodb-gateway-endpoint", Assignment = "3" })
}

# Wire DynamoDB Streams -> Lambda A
# parallelization_factor: up to 10 concurrent invocations per shard to reduce lag.
# function_response_types: partial batch failure -- only failed sequence numbers
#   are retried; stream position advances past successes.
resource "aws_lambda_event_source_mapping" "dynamo_stream_to_cdc" {
  count = local.create_lambda ? 1 : 0

  event_source_arn         = aws_dynamodb_table.messages_by_id[0].stream_arn
  function_name            = aws_lambda_function.cdc_projector[0].arn
  starting_position        = "LATEST"
  batch_size               = 100
  parallelization_factor   = var.lambda_cdc_parallelization_factor
  function_response_types  = ["ReportBatchItemFailures"]
}

# -- Lambda B: Redis Analytics --------------------------------------------------
# Consumes AnalyticsEvent payloads from SQS and records analytics in Redis via
# a single Lua batch call per invocation.  Placed in VPC when ElastiCache is
# used so it can reach the Redis cluster.

resource "aws_lambda_function" "redis_analytics" {
  count = local.create_lambda ? 1 : 0

  function_name    = local.lambda_analytics_name
  role             = local.resolved_lambda_role_arn
  runtime          = var.lambda_runtime
  handler          = "com.chatflow.cdc.RedisAnalyticsHandler::handleRequest"
  memory_size      = var.lambda_memory_mb
  timeout          = var.lambda_timeout_seconds
  filename         = local.lambda_uses_local_file ? var.lambda_cdc_jar_local_path : null
  source_code_hash = local.lambda_uses_local_file ? filebase64sha256(var.lambda_cdc_jar_local_path) : null
  s3_bucket        = local.lambda_uses_local_file ? null : var.lambda_cdc_jar_s3_bucket
  s3_key           = local.lambda_uses_local_file ? null : var.lambda_cdc_jar_s3_key

  environment {
    variables = {
      DYNAMO_REGION               = data.aws_region.current.name
      REDIS_ENDPOINT              = local.redis_endpoint
      REDIS_DEDUPE_EXPIRE_SECONDS = "3600"
      # DynamoDB table names not needed -- Lambda B does not write projections
    }
  }

  dynamic "vpc_config" {
    for_each = local.lambda_analytics_uses_vpc ? [1] : []
    content {
      subnet_ids         = local.persistence_subnet_ids
      security_group_ids = [aws_security_group.cdc_lambda[0].id]
    }
  }

  tags = merge(local.tags, { Assignment = "3" })
}

# Wire SQS analytics queue -> Lambda B
# maximum_batching_window_in_seconds: buffer up to 5 seconds to fill a larger
#   batch, reducing Redis round-trips at the cost of slightly higher latency.
# scaling_config.maximum_concurrency: caps concurrent Lambda B instances so a
#   single-node Redis is not overwhelmed.
resource "aws_lambda_event_source_mapping" "sqs_to_analytics" {
  count = local.create_lambda ? 1 : 0

  event_source_arn                   = aws_sqs_queue.cdc_analytics_queue[0].arn
  function_name                      = aws_lambda_function.redis_analytics[0].arn
  batch_size                         = var.lambda_analytics_batch_size
  maximum_batching_window_in_seconds = 5
  function_response_types            = ["ReportBatchItemFailures"]

  dynamic "scaling_config" {
    for_each = var.lambda_analytics_max_concurrency > 0 ? [1] : []
    content {
      maximum_concurrency = var.lambda_analytics_max_concurrency
    }
  }
}

# -- ElastiCache Redis (analytics) ---------------------------------------------

resource "aws_security_group" "redis_analytics" {
  count = (var.enable_persistence && var.enable_elasticache) ? 1 : 0

  name_prefix = "${var.name_prefix}-redis-analytics-"
  description = "Redis analytics SG for Assignment 3."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port = 6379
    to_port   = 6379
    protocol  = "tcp"
    security_groups = [
      aws_security_group.server.id,
      aws_security_group.consumer.id,
      aws_security_group.cdc_lambda[0].id
    ]
    description = "Redis access from server-v2, consumer-v3 and CDC Lambda"
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, { Assignment = "3" })
}

resource "aws_security_group" "cdc_lambda" {
  count = (var.enable_persistence && var.enable_elasticache) ? 1 : 0

  name_prefix = "${var.name_prefix}-cdc-lambda-"
  description = "Lambda SG for Assignment 3 CDC projector."
  vpc_id      = data.aws_vpc.default.id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, { Assignment = "3" })
}

resource "aws_elasticache_subnet_group" "redis_analytics" {
  count = (var.enable_persistence && var.enable_elasticache) ? 1 : 0

  name       = "${var.name_prefix}-redis-analytics"
  subnet_ids = local.persistence_subnet_ids

  tags = merge(local.tags, { Assignment = "3" })
}

resource "aws_elasticache_cluster" "redis_analytics" {
  count = (var.enable_persistence && var.enable_elasticache) ? 1 : 0

  cluster_id           = local.elasticache_cluster_id
  engine               = "redis"
  node_type            = var.elasticache_node_type
  num_cache_nodes      = var.elasticache_num_nodes
  engine_version       = var.elasticache_engine_version
  parameter_group_name = "default.redis7"
  subnet_group_name    = aws_elasticache_subnet_group.redis_analytics[0].name
  security_group_ids   = [aws_security_group.redis_analytics[0].id]

  tags = merge(local.tags, { Assignment = "3" })
}

# -- Data sources --------------------------------------------------------------

data "aws_region" "current" {}

# -- Outputs (Assignment 3) ----------------------------------------------------

output "dynamo_canonical_table_name" {
  description = "Name of the canonical DynamoDB messages table."
  value       = var.enable_persistence ? aws_dynamodb_table.messages_by_id[0].name : null
}

output "dynamo_canonical_table_arn" {
  description = "ARN of the canonical DynamoDB messages table."
  value       = var.enable_persistence ? aws_dynamodb_table.messages_by_id[0].arn : null
}

output "dynamo_stream_arn" {
  description = "DynamoDB Stream ARN for the canonical table (feed for CDC Lambda)."
  value       = (var.enable_persistence && var.dynamo_stream_enabled) ? aws_dynamodb_table.messages_by_id[0].stream_arn : null
}

output "sqs_dlq_url" {
  description = "SQS DLQ URL for consumer-v3 (set as CHATFLOW_V3_SQS_DLQ_URL env var)."
  value       = var.enable_persistence ? aws_sqs_queue.consumer_v3_dlq[0].url : null
}

output "sqs_dlq_arn" {
  description = "SQS DLQ ARN."
  value       = var.enable_persistence ? aws_sqs_queue.consumer_v3_dlq[0].arn : null
}

output "redis_endpoint" {
  description = "ElastiCache Redis endpoint (host:port). Set as REDIS_ENDPOINT in Lambda and consumer env vars."
  value       = local.redis_endpoint
}

output "lambda_function_name" {
  description = "Lambda A (CDC projector) function name."
  value       = local.create_lambda ? aws_lambda_function.cdc_projector[0].function_name : null
}

output "lambda_analytics_function_name" {
  description = "Lambda B (Redis analytics) function name."
  value       = local.create_lambda ? aws_lambda_function.redis_analytics[0].function_name : null
}

output "sqs_analytics_queue_url" {
  description = "SQS analytics queue URL (Lambda A -> Lambda B pipeline)."
  value       = local.create_lambda ? aws_sqs_queue.cdc_analytics_queue[0].url : null
}

output "sqs_analytics_queue_arn" {
  description = "SQS analytics queue ARN."
  value       = local.create_lambda ? aws_sqs_queue.cdc_analytics_queue[0].arn : null
}

# When existing_lambda_role_arn is set, Terraform cannot attach policies to the
# supplied role.  This output lists the permissions that role must already have
# for both Lambda A and Lambda B to function correctly.  Review this output
# after every apply when existing_lambda_role_arn is non-empty.
output "iam_requirements_for_existing_lambda_role" {
  description = "Permissions the externally-supplied Lambda role must have (only relevant when existing_lambda_role_arn is set)."
  value = var.existing_lambda_role_arn != "" && local.create_lambda ? {
    role_arn = var.existing_lambda_role_arn
    analytics_queue_arn = aws_sqs_queue.cdc_analytics_queue[0].arn
    lambda_a_requires = [
      "dynamodb:GetRecords,GetShardIterator,DescribeStream,ListStreams on stream",
      "dynamodb:PutItem,UpdateItem,GetItem,DescribeTable on projection tables",
      "sqs:SendMessage on analytics queue (NEW)",
      "logs:CreateLogGroup,CreateLogStream,PutLogEvents",
    ]
    lambda_b_requires = [
      "sqs:ReceiveMessage,DeleteMessage,ChangeMessageVisibility,GetQueueAttributes on analytics queue (NEW)",
      "logs:CreateLogGroup,CreateLogStream,PutLogEvents",
      "ec2 network interface permissions (only if enable_elasticache=true)",
    ]
    verify_command = "aws iam simulate-principal-policy --policy-source-arn ${var.existing_lambda_role_arn} --action-names sqs:SendMessage sqs:ReceiveMessage sqs:DeleteMessage --resource-arns ${aws_sqs_queue.cdc_analytics_queue[0].arn}"
  } : null
}
