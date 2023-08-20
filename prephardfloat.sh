#!/bin/bash

if [ ! -d berkeley-hardfloat/hardfloat ] ; then
	git clone https://github.com/ucb-bar/berkeley-hardfloat.git
fi

if [ ! -f src/main/scala/hardfloat/primitives.scala ] ; then
	echo "Copying hardfloat sources"
	mkdir -p src/main/scala/hardfloat
	cp berkeley-hardfloat/hardfloat/src/main/scala/* src/main/scala/hardfloat/
fi

echo
echo "Note: A workaround applied. Use hardfloat's sbt once Chisel 5.0 cross build is added"
echo

exit 0

