#!/bin/bash

# AWS Auto Scaling Cleanup Script
# This script removes all resources created by the auto scaling task

echo "=========================================="
echo "  AWS Auto Scaling Resource Cleanup"
echo "=========================================="

# Configuration (must match auto-scaling-config.json)
ASG_NAME="task2"
LAUNCH_TEMPLATE_NAME="asg-launch-template"
LOAD_BALANCER_NAME="asg-load-balancer"
TARGET_GROUP_NAME="asg-target-group"
ELB_SECURITY_GROUP="elb-asg-security-group"
LG_SECURITY_GROUP="lg-security-group"
PROJECT_TAG="vm-scaling"

echo -e "\n[1/7] Deleting Auto Scaling Group..."
aws autoscaling delete-auto-scaling-group --auto-scaling-group-name "$ASG_NAME" --force-delete 2>/dev/null && echo "  ASG deleted" || echo "  ASG not found or already deleted"

echo -e "\n[2/7] Deleting CloudWatch Alarms..."
aws cloudwatch delete-alarms --alarm-names "${ASG_NAME}-high-cpu-alarm" "${ASG_NAME}-low-cpu-alarm" 2>/dev/null && echo "  Alarms deleted" || echo "  Alarms not found"

echo -e "\n[3/7] Deleting Load Balancer..."
LB_ARN=$(aws elbv2 describe-load-balancers --names "$LOAD_BALANCER_NAME" --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null)
if [ -n "$LB_ARN" ] && [ "$LB_ARN" != "None" ]; then
    aws elbv2 delete-load-balancer --load-balancer-arn "$LB_ARN" && echo "  Load Balancer deleted"
else
    echo "  Load Balancer not found"
fi

echo -e "\n[4/7] Deleting Launch Template..."
aws ec2 delete-launch-template --launch-template-name "$LAUNCH_TEMPLATE_NAME" 2>/dev/null && echo "  Launch Template deleted" || echo "  Launch Template not found"

echo -e "\n[5/7] Terminating all project instances..."
INSTANCE_IDS=$(aws ec2 describe-instances \
    --filters "Name=tag:Project,Values=$PROJECT_TAG" "Name=instance-state-name,Values=running,pending" \
    --query 'Reservations[*].Instances[*].InstanceId' --output text | tr '\n' ' ')
if [ -n "$INSTANCE_IDS" ] && [ "$INSTANCE_IDS" != " " ]; then
    aws ec2 terminate-instances --instance-ids $INSTANCE_IDS >/dev/null && echo "  Instances terminated: $INSTANCE_IDS"
else
    echo "  No running instances found"
fi

echo -e "\n[6/7] Waiting for resources to be released (30s)..."
sleep 30

echo -e "\n[7/7] Deleting Target Group and Security Groups..."

# Delete Target Group
TG_ARN=$(aws elbv2 describe-target-groups --names "$TARGET_GROUP_NAME" --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null)
if [ -n "$TG_ARN" ] && [ "$TG_ARN" != "None" ]; then
    aws elbv2 delete-target-group --target-group-arn "$TG_ARN" && echo "  Target Group deleted"
else
    echo "  Target Group not found"
fi

# Wait a bit more for instances to fully terminate
echo "  Waiting for instances to fully terminate (30s)..."
sleep 30

# Delete Security Groups
aws ec2 delete-security-group --group-name "$ELB_SECURITY_GROUP" 2>/dev/null && echo "  ELB/ASG Security Group deleted" || echo "  ELB/ASG Security Group not found or still in use"
aws ec2 delete-security-group --group-name "$LG_SECURITY_GROUP" 2>/dev/null && echo "  LG Security Group deleted" || echo "  LG Security Group not found or still in use"

echo -e "\n=========================================="
echo "  Cleanup Complete!"
echo "=========================================="

# Verification
echo -e "\nVerifying cleanup..."
echo -n "  ASG: "
aws autoscaling describe-auto-scaling-groups --auto-scaling-group-names "$ASG_NAME" --query 'AutoScalingGroups[0].AutoScalingGroupName' --output text 2>/dev/null || echo "None"
echo -n "  Launch Template: "
aws ec2 describe-launch-templates --launch-template-names "$LAUNCH_TEMPLATE_NAME" --query 'LaunchTemplates[0].LaunchTemplateName' --output text 2>/dev/null || echo "None"
echo -n "  Load Balancer: "
aws elbv2 describe-load-balancers --names "$LOAD_BALANCER_NAME" --query 'LoadBalancers[0].LoadBalancerName' --output text 2>/dev/null || echo "None"
echo -n "  Target Group: "
aws elbv2 describe-target-groups --names "$TARGET_GROUP_NAME" --query 'TargetGroups[0].TargetGroupName' --output text 2>/dev/null || echo "None"
echo -n "  Running Instances: "
COUNT=$(aws ec2 describe-instances --filters "Name=tag:Project,Values=$PROJECT_TAG" "Name=instance-state-name,Values=running,pending" --query 'Reservations[*].Instances[*].InstanceId' --output text | wc -w)
echo "$COUNT"

echo -e "\nDone!"