#!/bin/bash

if [ -z "$1" ] ; then
	echo "$0 verilogfile.v"
	exit 0
fi

FN=$1
BN=`basename $FN`
TOP=${BN%%.sv}

cat <<EOF > tmp.ys
read -sv $FN
hierarchy -top $TOP
proc
memory
flatten
opt
stat
techmap -map +/techmap.v -D NO_DIV -D NO_EXPAND
opt
stat
EOF

#techmap
#opt
#stat


yosys tmp.ys

rm -f tmp.ys
