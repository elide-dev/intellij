##
## Elide IntelliJ Plugin
##

GRADLEW   := ./gradlew
BUMP_TYPE := $(filter major minor patch, $(MAKECMDGOALS))

.PHONY: codegen test verify dist publish release bump major minor patch

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

## Bump .version and create tag locally, without pushing  (make bump [major|minor|patch])
bump:
	tools/release.sh $(BUMP_TYPE) --no-push

## Bump, tag, and push to origin  (make release [major|minor|patch])
release:
	tools/release.sh $(BUMP_TYPE)

major minor patch:
	@:
