param(
    [string]$KeystorePath = (Join-Path $env:USERPROFILE 'FreeMyTV-signing\freemytv.keystore')
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keystore = (Resolve-Path -LiteralPath $KeystorePath).Path
$git = Get-Command git -ErrorAction Stop
$java = Get-Command java -ErrorAction Stop

if (-not (Test-Path (Join-Path $repoRoot '.git'))) {
    throw 'Run this from a Free My TV Git checkout.'
}

$match = $null
foreach ($revision in @('HEAD:build.sh', 'HEAD^1:build.sh', 'origin/main:build.sh')) {
    $legacyBuild = & $git.Source -C $repoRoot show $revision 2>$null
    if ($LASTEXITCODE -eq 0) {
        $candidate = [regex]::Match(($legacyBuild -join "`n"), '--ks-pass pass:([^\s\\]+)')
        if ($candidate.Success) {
            $match = $candidate
            break
        }
    }
}
if ($null -eq $match -or -not $match.Success) {
    throw 'The old local signing password was not found in this checkout. Do not guess; contact the project maintainer.'
}
$oldPassword = $match.Groups[1].Value
$match = $null
$legacyBuild = $null

$newSecure = Read-Host 'Enter your new memorable passphrase (at least 12 characters)' -AsSecureString
$confirmSecure = Read-Host 'Enter it again to confirm' -AsSecureString

$oldBstr = [IntPtr]::Zero
$newBstr = [IntPtr]::Zero
$confirmBstr = [IntPtr]::Zero
$helper = Join-Path ([System.IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString() + '.java')
try {
    if ($newSecure.Length -lt 12) {
        throw 'Use at least 12 characters. Choose a memorable passphrase; the keystore was not changed.'
    }
    $newBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($newSecure)
    $confirmBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($confirmSecure)
    $newText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($newBstr)
    $confirmText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($confirmBstr)
    if ($newText -cne $confirmText) {
        throw 'The two passphrases did not match. The keystore was not changed.'
    }

    $oldSecure = ConvertTo-SecureString -String $oldPassword -AsPlainText -Force
    $oldBstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($oldSecure)
    $env:FREEMYTV_ROTATE_OLD_PASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($oldBstr)
    $env:FREEMYTV_ROTATE_NEW_PASS = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($newBstr)
    $env:FREEMYTV_ROTATE_KEYSTORE = $keystore

    @'
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;

public class RotateFreeMyTVSigningPassword {
    public static void main(String[] args) throws Exception {
        char[] oldPassword = System.getenv("FREEMYTV_ROTATE_OLD_PASS").toCharArray();
        char[] newPassword = System.getenv("FREEMYTV_ROTATE_NEW_PASS").toCharArray();
        Path keystore = Path.of(System.getenv("FREEMYTV_ROTATE_KEYSTORE"));
        Path temp = keystore.resolveSibling(keystore.getFileName() + ".new");
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                store.load(in, oldPassword);
            }
            String alias = "freemytv";
            if (!store.containsAlias(alias) || !store.isKeyEntry(alias)) {
                throw new IllegalStateException("Expected private key alias was not found.");
            }
            Key privateKey = store.getKey(alias, oldPassword);
            Certificate[] chain = store.getCertificateChain(alias);
            store.setKeyEntry(alias, privateKey, newPassword, chain);
            try (OutputStream out = Files.newOutputStream(temp)) {
                store.store(out, newPassword);
            }

            KeyStore check = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(temp)) {
                check.load(in, newPassword);
            }
            check.getKey(alias, newPassword);
            byte[] fingerprint = MessageDigest.getInstance("SHA-256")
                    .digest(check.getCertificate(alias).getEncoded());
            StringBuilder hex = new StringBuilder();
            for (byte b : fingerprint) hex.append(String.format("%02x", b & 0xff));
            Files.move(temp, keystore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            System.out.println("Keystore password updated. Certificate SHA-256: " + hex);
        } finally {
            java.util.Arrays.fill(oldPassword, '\0');
            java.util.Arrays.fill(newPassword, '\0');
            Files.deleteIfExists(temp);
        }
    }
}
'@ | Set-Content -LiteralPath $helper -Encoding ASCII

    & $java.Source $helper
    if ($LASTEXITCODE -ne 0) {
        throw 'Password update failed. The original keystore was left in place.'
    }
}
finally {
    Remove-Item Env:FREEMYTV_ROTATE_OLD_PASS, Env:FREEMYTV_ROTATE_NEW_PASS, Env:FREEMYTV_ROTATE_KEYSTORE -ErrorAction SilentlyContinue
    if ($oldBstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($oldBstr) }
    if ($newBstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($newBstr) }
    if ($confirmBstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($confirmBstr) }
    if ($oldSecure) { $oldSecure.Dispose() }
    $oldPassword = $null
    $newText = $null
    $confirmText = $null
    Remove-Item -LiteralPath $helper -ErrorAction SilentlyContinue
}
