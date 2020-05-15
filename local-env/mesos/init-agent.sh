#!/bin/bash

export MASTER_IP=$1
export ZOOKEEPER_URI=zk://${MASTER_IP}:2181/mesos
export ETCD_URI=$1:2379
export IP=$2

cp /vagrant/mesos/mesos-slave /etc/default/mesos-slave
echo "${ZOOKEEPER_URI}" > /etc/mesos/zk
mkdir /etc/mesos-agent
echo "${ZOOKEEPER_URI}" > /etc/mesos-agent/master
echo "/var/lib/mesos" > /etc/mesos-agent/work_dir
echo "docker,mesos" > /etc/mesos-agent/containerizers
echo "5051" > /etc/mesos-agent/port
echo "docker" > /etc/mesos-agent/image_providers
echo "${IP}" > /etc/mesos-agent/ip
echo "filesystem/linux,docker/runtime,network/cni" > /etc/mesos-agent/isolation
echo "" > /etc/mesos-agent/?no-hostname_lookup

systemctl enable firewalld
systemctl start firewalld
firewall-cmd --zone=public --add-port=5051/tcp --permanent
firewall-cmd --reload

systemctl daemon-reload
systemctl enable mesos-slave
systemctl start mesos-slave

########################### INSTALL DOCKER ############################

yum install -y yum-utils
yum-config-manager \
    --add-repo \
    https://download.docker.com/linux/centos/docker-ce.repo
yum -y install docker-ce docker-ce-cli containerd.io

mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<EOF
{
  "cluster-store": "etcd://${ETCD_URI}"
}
EOF

systemctl daemon-reload
systemctl enable docker
systemctl start docker


####################### Install CALICO ####################################
mkdir -p /var/run/calico /var/log/calico /var/lib/calico /etc/calico
cat > /etc/calico/calico.env <<EOF
DATASTORE_TYPE=etcdv3
ETCD_ENDPOINTS=http://${ETCD_URI}
ETCD_CA_CERT_FILE="/pki/ca.pem"
ETCD_CERT_FILE="/pki/client-cert.pem"
ETCD_KEY_FILE="/pki/client-key.pem"
CALICO_NODENAME=""
NO_DEFAULT_POOLS="true"
CALICO_IP=""
CALICO_IP6=""
CALICO_AS=""
CALICO_NETWORKING_BACKEND=bird
EOF
curl -L  https://github.com/projectcalico/calicoctl/releases/download/v3.13.2/calicoctl -C /usr/bin/calicoctl
chmod +x /usr/bin/calicoctl
cp /vagrant/calico/calico.service /etc/systemd/system/calico.service

systemctl daemon-reload
systemctl enable calico
systemctl start calico

systemctl enable firewalld
systemctl start firewalld
firewall-cmd --zone=public --add-port=179/tcp --permanent
firewall-cmd --zone=public --add-port=4/tcp --permanent
firewall-cmd --zone=public --add-port=4789/udp --permanent
firewall-cmd --zone=public --add-port=5473/tcp --permanent
firewall-cmd --reload

