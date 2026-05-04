##
## Elide IntelliJ Plugin
##

GRADLEW   := ./gradlew
BUMP_TYPE := $(filter major minor patch, $(MAKECMDGOALS))

.PHONY: dist publish release bump major minor patch

## Build the plugin ZIP distribution
dist:
	$(GRADLEW) buildPlugin

## Build and publish the plugin to the remote repository
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
