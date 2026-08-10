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
docker build --no-cache -f local-docker-testing/Dockerfile -t local-monolith .
echo "Build process completed successfully!"

cd local-docker-testing

echo "Optional: attempting to use trivy to scan image"
trivy image --severity HIGH,CRITICAL --scanners vuln --output results.txt local-monolith:latest
echo "Done scanning imgae, output file in results.txt"
