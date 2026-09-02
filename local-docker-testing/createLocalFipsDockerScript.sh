#!/bin/bash

# Exit on any error
set -e

echo "Starting build process..."

# Navigate up one folder and into Semoss
echo "Building Semoss..."
cd ../../Semoss
mvn clean install -U -DskipTests=true

# Navigate up one folder and into Monolith
# The fips profile keeps BouncyCastle out of the war, the container supplies it
echo "Building Monolith..."
cd ../Monolith
mvn clean install -U -DskipTests=true -P dev,fips

# Build Docker image from parent directory to access target folder
echo "Building Docker image..."
docker build --no-cache -f local-docker-testing/Dockerfile.fips -t local-monolith-fips .
echo "Build process completed successfully!"

cd local-docker-testing

# Optional: pass --push to publish, or set IMAGE_TAG to retag first
if [ "$1" = "--push" ]; then
    docker tag local-monolith-fips "${IMAGE_TAG:-local-monolith-fips}"
    docker push "${IMAGE_TAG:-local-monolith-fips}"
fi

echo "Optional: attempting to use trivy to scan image"
trivy image --severity HIGH,CRITICAL --scanners vuln --output results-fips.txt local-monolith-fips:latest
echo "Done scanning image, output file in results-fips.txt"
