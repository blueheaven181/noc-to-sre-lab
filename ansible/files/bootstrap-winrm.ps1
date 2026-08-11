# Run this ONCE, manually, via RDP or console access - before Ansible can reach
# this host at all. Ansible can't bootstrap WinRM on a box it can't yet connect to.
# Self-signed cert is fine for a home lab; do not do this on anything internet-facing.

winrm quickconfig -q
winrm set winrm/config/service/auth '@{Basic="true"}'
winrm set winrm/config/service '@{AllowUnencrypted="false"}'
winrm set winrm/config/client/auth '@{Basic="true"}'

$cert = New-SelfSignedCertificate -DnsName "$env:COMPUTERNAME" -CertStoreLocation Cert:\LocalMachine\My
$thumbprint = $cert.Thumbprint

winrm create winrm/config/Listener?Address=*+Transport=HTTPS "@{Hostname=`"$env:COMPUTERNAME`";CertificateThumbprint=`"$thumbprint`"}"

New-NetFirewallRule -Name "WinRM-HTTPS" -DisplayName "WinRM over HTTPS" -Protocol TCP -LocalPort 5986 -Action Allow

Write-Host "WinRM HTTPS listener configured on port 5986. Ansible can now reach this host."
