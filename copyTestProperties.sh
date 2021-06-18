#!/bin/bash
#
# Sort TEST property files
# Update the REFERENCE property files (from TEST files)
#

./sortTestProperties.sh

echo "copying test properties"
cp -v ./src/test/resources/test/*.properties ./src/test/resources/ref/

echo "copying OIFITS-report.log"
cp ./src/test/resources/test/OIFITS-report.log ./src/test/resources/ref/

