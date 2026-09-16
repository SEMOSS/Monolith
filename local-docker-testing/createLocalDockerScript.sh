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

echo "Staging Semoss js folder for the node execution environment..."
rm -rf target/docker-js
cp -R ../Semoss/js target/docker-js
rm -rf target/docker-js/node_env/node_modules

# Build Docker image from parent directory to access target folder
echo "Building Docker image..."
docker build --no-cache -f local-docker-testing/Dockerfile -t local-monolith .
echo "Build process completed successfully!"

cd local-docker-testing

echo "Optional: attempting to use trivy to scan image"
trivy image --severity HIGH,CRITICAL --scanners vuln --output results.txt local-monolith:latest
echo "Done scanning imgae, output file in results.txt"
