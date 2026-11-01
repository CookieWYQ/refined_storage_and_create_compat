#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SFTP 连接库（只读凭据，不打印任何密码）。

凭据文件与 sftp_skill.py 完全相同（.sftp_key + sftp_credentials.enc），
但这里独立实现，**不改动 sftp_skill.py 的任何逻辑**。
"""
import json
from pathlib import Path

import paramiko
from cryptography.fernet import Fernet

BASE = Path(__file__).resolve().parent.parent
KEY = BASE / ".sftp_key"
CRED = BASE / "sftp_credentials.enc"


def creds():
    fernet = Fernet(KEY.read_bytes())
    return json.loads(fernet.decrypt(CRED.read_bytes()).decode("utf-8"))


def aliases():
    return list(creds().keys())


def connect(alias):
    cfg = creds()[alias]
    transport = paramiko.Transport((cfg["host"], cfg["port"]))
    transport.connect(username=cfg["user"], password=cfg["pass"])
    return paramiko.SFTPClient.from_transport(transport), transport
