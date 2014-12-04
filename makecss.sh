#!/bin/sh

# Prerequisites:
# npm install -g less (npm i less --save-dev)
# npm install -g less-plugin-clean-css

lessc --clean-css="--s1 --advanced" --verbose -x src/main/resources/less/ci.less > src/main/resources/assets/css/ci.css