#!/bin/bash

if [ -z "$1" ]; then
    echo "Usage $0 version"
    exit 1
fi

VERSION="$1"
echo "Setting version to $VERSION"

mvn versions:set "-DnewVersion=$VERSION"

git add -u 
git commit -m "Bump version before tagging"
git tag -a "$VERSION" -m "Tag $VERSION"
git push
git push origin "$VERSION"
