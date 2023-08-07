#!/bin/bash

if [ ! -d hardfloat ] ; then
	git clone https://github.com/ucb-bar/berkeley-hardfloat.git hardfloat
fi

if [ ! -d src/main/scala/hardfloat ] ; then
	echo "Copying hardfloat sources"
	mkdir -p src/main/scala/hardfloat
	cp hardfloat/src/main/scala/* src/main/scala/hardfloat/
fi

echo "A workaround applied. Once hardfloat is updated for Chisel 5.0.0, use git submodule"

exit 0

