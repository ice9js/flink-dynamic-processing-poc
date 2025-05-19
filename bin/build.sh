#!/bin/bash

set -e

cd "$(dirname "$0")/../jobs"

for dir in */ ; do
	echo "Building $dir..."
	mvn -f "$dir/pom.xml" clean package

	cp $dir/target/*-SNAPSHOT.jar ../docker/flink/jobs/
done
