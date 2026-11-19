JAVAC ?= javac
JAVA ?= java

.PHONY: test clean

test:
	mkdir -p build
	$(JAVAC) --release 17 -d build $(shell find src -name '*.java')
	$(JAVA) -cp build en16931.AllTests

clean:
	rm -rf build
