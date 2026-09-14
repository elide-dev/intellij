##
## Elide IntelliJ Plugin
##

GRADLEW := ./gradlew

.PHONY: codegen test verify dist publish

## Regenerate the manifest model from Elide's published Pkl schema (requires `brine`)
codegen:
	tools/codegen.sh

## Run the unit tests
test:
	$(GRADLEW) test

## Check the plugin descriptor and binary compatibility across the supported IDE range
verify:
	$(GRADLEW) verifyPluginProjectConfiguration verifyPlugin

## Build the plugin ZIP distribution
dist:
	$(GRADLEW) buildPlugin

## Build and publish the plugin to the Elide plugin repository
publish: dist
	tools/deploy.sh
