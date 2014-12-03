#!/bin/sh

# Prerequisites:
# npm install -g less
# npm install -g less-plugin-clean-css

lessc --clean-css="--s1 --advanced" --verbose src/main/resources/less/ci.less > src/main/resources/assets/css/ci.css