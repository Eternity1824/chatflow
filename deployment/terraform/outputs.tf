output "alb_dns_name" {
  description = "ALB DNS endpoint for clients."
  value       = aws_lb.chatflow.dns_name
}

output "client_websocket_url" {
  description = "WebSocket URL clients should use (via ALB)."
  value       = "ws://${aws_lb.chatflow.dns_name}/chat"
}

output "server_private_ips" {
  description = "Private IPs of ChatFlow server nodes."
  value       = aws_instance.server[*].private_ip
}

output "server_public_ips" {
  description = "Public IPs of ChatFlow server nodes."
  value       = aws_instance.server[*].public_ip
}

output "rabbit_private_ip" {
  description = "RabbitMQ private IP."
  value       = aws_instance.rabbit.private_ip
}

output "rabbit_public_ip" {
  description = "RabbitMQ public IP."
  value       = aws_instance.rabbit.public_ip
}

output "consumer_private_ip" {
  description = "Consumer private IP."
  value       = aws_instance.consumer.private_ip
}

output "consumer_public_ip" {
  description = "Consumer public IP."
  value       = aws_instance.consumer.public_ip
}

output "consumer_broadcast_targets" {
  description = "Computed internal broadcast targets written to consumer env."
  value       = [for ip in aws_instance.server[*].private_ip : "http://${ip}:8080"]
}
