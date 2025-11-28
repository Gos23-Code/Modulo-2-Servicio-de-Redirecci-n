terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-east-1"
}

variable "dynamo_table" {
  default = "shortener-dynamo-table"
}

# IAM Role para Lambda
resource "aws_iam_role" "redirect_lambda_role" {
  name = "redirect-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_basic" {
  role       = aws_iam_role.redirect_lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Permisos para DynamoDB
resource "aws_iam_role_policy" "dynamodb_access" {
  name = "redirect-dynamodb-access"
  role = aws_iam_role.redirect_lambda_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "dynamodb:GetItem",
        "dynamodb:Query",
        "dynamodb:UpdateItem"
      ]
      Resource = "arn:aws:dynamodb:us-east-1:*:table/${var.dynamo_table}"
    }]
  })
}

# Lambda Function
resource "aws_lambda_function" "redirect" {
  function_name = "redirect-lambda"

  runtime = "java17"
  handler = "com.shortener.RedirectHandler::handleRequest"

  role = aws_iam_role.redirect_lambda_role.arn

  filename         = "../build/distributions/redirect-handler.zip"
  source_code_hash = filebase64sha256("../build/distributions/redirect-handler.zip")

  timeout = 30
  memory_size = 512

  environment {
    variables = {
      TABLE_NAME = var.dynamo_table
    }
  }
}

# API Gateway
resource "aws_apigatewayv2_api" "redirect_api" {
  name          = "redirect-api"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "lambda_integration" {
  api_id             = aws_apigatewayv2_api.redirect_api.id
  integration_type   = "AWS_PROXY"
  integration_uri    = aws_lambda_function.redirect.invoke_arn
  payload_format_version = "2.0"
}

# Ruta para redirección GET /{code}
resource "aws_apigatewayv2_route" "redirect_route" {
  api_id    = aws_apigatewayv2_api.redirect_api.id
  route_key = "GET /{code}"
  target    = "integrations/${aws_apigatewayv2_integration.lambda_integration.id}"
}

resource "aws_lambda_permission" "lambda_permission" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.redirect.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.redirect_api.execution_arn}/*/*"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.redirect_api.id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_logs.arn
    format          = "$context.requestId $context.status $context.error.message"
  }
}

resource "aws_cloudwatch_log_group" "api_logs" {
  name = "/aws/apigateway/redirect-api"
}

resource "aws_cloudwatch_log_group" "lambda_logs" {
  name = "/aws/lambda/redirect-lambda"
  retention_in_days = 7
}

output "redirect_api_url" {
  value = aws_apigatewayv2_api.redirect_api.api_endpoint
}

output "lambda_function_name" {
  value = aws_lambda_function.redirect.function_name
}