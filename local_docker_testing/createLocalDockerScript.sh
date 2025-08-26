#!/bin/bash

# Exit on any error
set -e

echo "Starting build process..."

# Navigate up one folder and into Semoss
echo "Building Semoss..."
cd ../../Semoss
mvn clean install -U -DskipTests=true

# Navigate up one folder and into Monolith
echo "Building Monolith..."
cd ../Monolith
mvn clean install -U -DskipTests=true

# Build Docker image from parent directory to access target folder
echo "Building Docker image..."
docker build --no-cache -f local_docker_testing/Dockerfile -t local-monolith .

echo "Build process completed successfully!"