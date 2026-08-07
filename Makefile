.PHONY: install deploy

install:
	chmod +x ./gradlew
	./gradlew :app:assembleDebug

deploy:
	cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/