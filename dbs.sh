#!/usr/bin/env bash
# This script is necessary to run integration tests
host=$1
port=$2
drop_mode=$3

command=createdb
if [ -n "$drop_mode" ]; then
echo "!!!!!!!!!!!!!!!!!!!!!"
echo "!!DROPPING DBS MODE!!"
echo "!!!!!!!!!!!!!!!!!!!!!"
command=dropdb
fi

echo "==> PostgreSQL server infos: host " $host ", port " $port

# Databases used in IT tests
tests=(currency_pool test_pool test_pool1 op_pool_mal info_pool exist_pool fresh_addresses_pool list_pool account_pool balance_pool pool_1 pool_2 pool_3 random_pool multi_pool_mal transactionsCreation4Test ledger anotha_pool same_pool this_pool my_pool your_pool wallet_pool duplicate_pool empty_pool multi_pool random POOL_NAME myPool test_wallet)

# Create all DBs with names above
for elem in "${tests[@]}"
do
    $command -h $host -p $port $elem
done




