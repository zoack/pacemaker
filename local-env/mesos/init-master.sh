#!/bin/bash

export IP=$1
export ZOOKEEPER_URI=zk://${IP}:2181/mesos
export ETCD_URI=http://${IP}
cp /vagrant/mesos/mesos-master /etc/default/mesos-master
echo "${ZOOKEEPER_URI}" > /etc/mesos/zk
echo "IP=${IP}" >> /etc/default/mesos-master
echo "LIBPROCESS_IP=${IP}" >> /etc/default/mesos-master

systemctl enable firewalld
systemctl start firewalld
firewall-cmd --zone=public --add-port=5050/tcp --permanent
firewall-cmd --reload

systemctl enable mesos-master
systemctl start mesos-master
########### ZOOKEEPER installation ##########
yum install -y java-1.8.0-openjdk

groupadd zookeeper
adduser zookeeper -g zookeeper

mkdir -p /usr/share/zookeeper/ /var/data/zookeeper /var/log/zookeeper
chown -R zookeeper:zookeeper /var/data/zookeeper /usr/share/zookeeper/ /var/log/zookeeper

wget -qO- https://downloads.apache.org/zookeeper/zookeeper-3.5.7/apache-zookeeper-3.5.7-bin.tar.gz | tar  --strip-components 1 -xz -C /usr/share/zookeeper


cp /vagrant/zookeeper/zoo.cfg /usr/share/zookeeper/conf/
cp /vagrant/zookeeper/zookeeper.service /etc/systemd/system/zookeeper.service

systemctl enable firewalld
systemctl start firewalld
firewall-cmd --zone=public --add-port=2181/tcp --permanent
firewall-cmd --zone=public --add-port=2888/tcp --permanent
firewall-cmd --zone=public --add-port=3888/tcp --permanent
firewall-cmd --reload

systemctl daemon-reload
systemctl enable zookeeper
systemctl start zookeeper

############ ETCD installation ###############
groupadd etcd
adduser etcd -g etcd

yum install -y etcd

mkdir -p /var/data/etcd /var/wal/etcd /etc/etcd
cp /vagrant/etcd/etcd.service /etc/systemd/system/etcd.service
cp /vagrant/etcd/etcd.yml /etc/etcd/etcd.yml

echo "listen-peer-urls: $ETCD_URI:2380" >> /etc/etcd/etcd.yml
echo "listen-client-urls: $ETCD_URI:2379" >> /etc/etcd/etcd.yml
#echo "initial-advertise-peer-urls: $ETCD_URI:2380" >> /etc/etcd/etcd.yml
#echo "advertise-client-urls: $ETCD_URI:2379" >> /etc/etcd/etcd.yml
#echo "initial-cluster: $ETCD_URI:2380" >> /etc/etcd/etcd.yml

chown -R etcd:etcd /var/data/etcd /var/wal/ /etc/etcd

systemctl enable firewalld
systemctl start firewalld
firewall-cmd --zone=public --add-port=2379/tcp --permanent
firewall-cmd --zone=public --add-port=2380/tcp --permanent
firewall-cmd --reload

systemctl daemon-reload
systemctl enable etcd
systemctl start etcd


########### MESOS DNS ######################
export DNS_PORT=53
mkdir /usr/local/mesos-dns /var/log/mesos-dns
wget -O /usr/local/mesos-dns/mesos-dns https://github.com/mesosphere/mesos-dns/releases/download/v0.7.0-rc2/mesos-dns-v0.7.0-rc2-linux-amd64
chmod +x /usr/local/mesos-dns/mesos-dns  
cat > /usr/local/mesos-dns/config.json <<EOF
{
  "zk": "${ZOOKEEPER_URI}",
  "refreshSeconds": 60,
  "ttl": 60,
  "domain": "mesos",
  "port": ${DNS_PORT},
  "timeout": 5,
  "email": "root.mesos-dns.mesos"
}
EOF
cp /vagrant/mesos/mesos-dns.service /etc/systemd/system/mesos-dns.service

systemctl enable firewalld
systemctl start firewalld
firewall-cmd --zone=public --add-port=8123/tcp --permanent
firewall-cmd --zone=public --add-port=${DNS_PORT}/tcp --permanent
firewall-cmd --zone=public --add-port=${DNS_PORT}/udp --permanent
firewall-cmd --reload


systemctl daemon-reload
systemctl enable mesos-dns
systemctl start mesos-dns
