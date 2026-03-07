data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

data "aws_ami" "ubuntu_24_04" {
  most_recent = true
  owners      = ["099720109477"]

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

locals {
  tags = {
    Project     = "ChatFlow"
    Environment = var.environment
    ManagedBy   = "Terraform"
  }

  subnet_ids        = sort(data.aws_subnets.default.ids)
  key_name_or_null  = var.key_name == "" ? null : var.key_name
  server_private_ips = aws_instance.server[*].private_ip
  broadcast_targets = [for ip in local.server_private_ips : "http://${ip}:${var.chat_port}"]
}

resource "aws_security_group" "alb" {
  name_prefix = "${var.name_prefix}-alb-"
  description = "ALB ingress for ChatFlow."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = var.allowed_public_cidrs
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, { Name = "${var.name_prefix}-alb-sg" })
}

resource "aws_security_group" "server" {
  name_prefix = "${var.name_prefix}-server-"
  description = "WebSocket server SG."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port       = var.chat_port
    to_port         = var.chat_port
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
    description     = "ALB to server"
  }

  ingress {
    from_port       = var.chat_port
    to_port         = var.chat_port
    protocol        = "tcp"
    security_groups = [aws_security_group.consumer.id]
    description     = "Consumer internal broadcast"
  }

  dynamic "ingress" {
    for_each = var.admin_cidrs
    content {
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
      description = "SSH admin"
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, { Name = "${var.name_prefix}-server-sg" })
}

resource "aws_security_group" "rabbit" {
  name_prefix = "${var.name_prefix}-rabbit-"
  description = "RabbitMQ SG."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    from_port       = var.rabbit_port
    to_port         = var.rabbit_port
    protocol        = "tcp"
    security_groups = [aws_security_group.server.id, aws_security_group.consumer.id]
    description     = "AMQP from server/consumer"
  }

  dynamic "ingress" {
    for_each = var.admin_cidrs
    content {
      from_port   = var.rabbit_management_port
      to_port     = var.rabbit_management_port
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
      description = "Rabbit management UI"
    }
  }

  dynamic "ingress" {
    for_each = var.admin_cidrs
    content {
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
      description = "SSH admin"
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, { Name = "${var.name_prefix}-rabbit-sg" })
}

resource "aws_security_group" "consumer" {
  name_prefix = "${var.name_prefix}-consumer-"
  description = "Consumer SG."
  vpc_id      = data.aws_vpc.default.id

  dynamic "ingress" {
    for_each = var.admin_cidrs
    content {
      from_port   = var.consumer_health_port
      to_port     = var.consumer_health_port
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
      description = "Consumer health/metrics"
    }
  }

  dynamic "ingress" {
    for_each = var.admin_cidrs
    content {
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
      description = "SSH admin"
    }
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(local.tags, { Name = "${var.name_prefix}-consumer-sg" })
}

resource "aws_lb" "chatflow" {
  name               = "${var.name_prefix}-alb"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = local.subnet_ids
  idle_timeout       = var.alb_idle_timeout_seconds

  tags = merge(local.tags, { Name = "${var.name_prefix}-alb" })
}

resource "aws_lb_target_group" "server_tg" {
  name        = "${var.name_prefix}-ws-tg"
  port        = var.chat_port
  protocol    = "HTTP"
  target_type = "instance"
  vpc_id      = data.aws_vpc.default.id

  health_check {
    enabled             = true
    path                = "/health"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
    matcher             = "200-399"
  }

  stickiness {
    enabled         = true
    type            = "lb_cookie"
    cookie_duration = var.alb_sticky_cookie_seconds
  }

  tags = merge(local.tags, { Name = "${var.name_prefix}-ws-tg" })
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.chatflow.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.server_tg.arn
  }
}

resource "aws_instance" "rabbit" {
  ami                         = data.aws_ami.ubuntu_24_04.id
  instance_type               = var.rabbit_instance_type
  subnet_id                   = local.subnet_ids[0]
  vpc_security_group_ids      = [aws_security_group.rabbit.id]
  associate_public_ip_address = var.associate_public_ip
  key_name                    = local.key_name_or_null
  user_data = templatefile("${path.module}/user_data/rabbitmq.sh.tftpl", {
    rabbit_username = var.rabbit_username
    rabbit_password = var.rabbit_password
  })

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
  }

  tags = merge(local.tags, { Name = "${var.name_prefix}-rabbit" })
}

resource "aws_instance" "server" {
  count                       = var.server_count
  ami                         = data.aws_ami.ubuntu_24_04.id
  instance_type               = var.server_instance_type
  subnet_id                   = local.subnet_ids[count.index % length(local.subnet_ids)]
  vpc_security_group_ids      = [aws_security_group.server.id]
  associate_public_ip_address = var.associate_public_ip
  key_name                    = local.key_name_or_null

  user_data = templatefile("${path.module}/user_data/server.sh.tftpl", {
    server_id             = "server-${count.index + 1}"
    chat_port             = var.chat_port
    internal_token        = var.chatflow_internal_token
    rabbit_host           = aws_instance.rabbit.private_ip
    rabbit_port           = var.rabbit_port
    rabbit_username       = var.rabbit_username
    rabbit_password       = var.rabbit_password
    rabbit_vhost          = var.rabbit_vhost
    rabbit_exchange       = var.rabbit_exchange
    room_start            = var.room_start
    room_end              = var.room_end
    queue_message_ttl_ms  = var.queue_message_ttl_ms
    queue_max_length      = var.queue_max_length
    server_jar_url        = var.server_jar_url
  })

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
  }

  tags = merge(local.tags, { Name = "${var.name_prefix}-server-${count.index + 1}" })
}

resource "aws_lb_target_group_attachment" "server_attach" {
  count            = var.server_count
  target_group_arn = aws_lb_target_group.server_tg.arn
  target_id        = aws_instance.server[count.index].id
  port             = var.chat_port
}

resource "aws_instance" "consumer" {
  ami                         = data.aws_ami.ubuntu_24_04.id
  instance_type               = var.consumer_instance_type
  subnet_id                   = local.subnet_ids[0]
  vpc_security_group_ids      = [aws_security_group.consumer.id]
  associate_public_ip_address = var.associate_public_ip
  key_name                    = local.key_name_or_null

  user_data = templatefile("${path.module}/user_data/consumer.sh.tftpl", {
    rabbit_host                      = aws_instance.rabbit.private_ip
    rabbit_port                      = var.rabbit_port
    rabbit_username                  = var.rabbit_username
    rabbit_password                  = var.rabbit_password
    rabbit_vhost                     = var.rabbit_vhost
    rabbit_exchange                  = var.rabbit_exchange
    room_start                       = var.room_start
    room_end                         = var.room_end
    queue_message_ttl_ms             = var.queue_message_ttl_ms
    queue_max_length                 = var.queue_max_length
    consumer_threads                 = var.consumer_threads
    consumer_prefetch                = var.consumer_prefetch
    consumer_max_retries             = var.consumer_max_retries
    consumer_retry_backoff_base_ms   = var.consumer_retry_backoff_base_ms
    consumer_retry_backoff_max_ms    = var.consumer_retry_backoff_max_ms
    consumer_health_port             = var.consumer_health_port
    consumer_dedup_max_entries       = var.consumer_dedup_max_entries
    consumer_dedup_ttl_ms            = var.consumer_dedup_ttl_ms
    internal_token                   = var.chatflow_internal_token
    broadcast_targets                = join(",", local.broadcast_targets)
    consumer_jar_url                 = var.consumer_jar_url
  })

  root_block_device {
    volume_size = var.root_volume_size_gb
    volume_type = "gp3"
  }

  depends_on = [aws_instance.server]

  tags = merge(local.tags, { Name = "${var.name_prefix}-consumer" })
}
