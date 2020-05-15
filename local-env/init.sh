#!/bin/bash
echo "pacemaker" | passwd root --stdin

cp /vagrant/ssh_config /etc/ssh/ssh_config
sudo sed -i "/^[^#]*PasswordAuthentication[[:space:]]no/c\PasswordAuthentication yes" /etc/ssh/sshd_config
systemctl restart sshd

yum update


########### MESOS installation ###########
yum install -y wget vim cyrus-sasl-md5 libapr-1.so.x86_64  libaprutil-1.so.0 libsvn_delta-1.so.0 libsvn_subr-1.so.0 ntp apr-util svn 
wget https://apache.bintray.com/mesos/el7/x86_64/mesos-1.9.0-1.el7.x86_64.rpm

groupadd mesos
adduser mesos -g mesos

rpm -Uvh mesos-1.9.0-1.el7.x86_64.rpm
rm mesos-1.9.0-1.el7.x86_64.rpm


