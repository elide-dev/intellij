##
## Elide IntelliJ Plugin
##

GRADLEW := ./gradlew

.PHONY: dist release publish

## Build the plugin ZIP distribution
dist:
	$(GRADLEW) buildPlugin

## Build and publish the plugin to the remote repository
publish: dist
	tools/deploy.sh
