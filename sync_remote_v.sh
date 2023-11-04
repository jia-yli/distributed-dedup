tar -czvf generated_rtl.tar.gz ./generated_rtl
# these 2 lines are not tested
# ssh alveo-build "cd ~/projects/dedup; rm generated_rtl.tar.gz; rm -rf generated_rtl"
# scp generated_rtl.tar.gz alveo-build:/home/jiayli/projects/dedup/
tar -xzvf generated_rtl.tar.gz
