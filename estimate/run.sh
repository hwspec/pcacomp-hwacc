VF=`ls -1 ../generated/LocalRed*.sv`
#VF='../generated/BaseLinePCAComp_16x16_bw10_iembw8_sz50.sv'

for fn in $VF; do
	echo $f
    BN=`basename $fn`
    TOP=${BN%%.sv}
#	./yosys-fpga-techmap-stat.sh  $fn | tee -a stat_lut_$TOP.txt
	./yosys-techmap-stat.sh $fn | tee -a stat_cells_$TOP.txt
done
