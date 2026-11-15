param([string]$Output = "$PSScriptRoot\Island.apk", [string]$SigningKey = "$PSScriptRoot\debug.keystore")
$ErrorActionPreference = 'Stop'
function Run([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program exited with $LASTEXITCODE" }
}
$sdk = $env:ANDROID_HOME
if (!$sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$tools = "$sdk\build-tools\36.0.0"
$android = "$sdk\platforms\android-35\android.jar"
$java = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\" } else { "" }
$env:PATH = "$java;$env:PATH"
$build = "$PSScriptRoot\.build"
New-Item -ItemType Directory -Force $build,"$build\generated","$build\classes","$build\dex","$build\vlc" | Out-Null
$aar = "$build\libvlc.aar"
if (!(Test-Path $aar)) {
    Invoke-WebRequest https://repo.maven.apache.org/maven2/org/videolan/android/libvlc-all/3.6.5/libvlc-all-3.6.5.aar -OutFile "$aar.download"
    Move-Item -LiteralPath "$aar.download" -Destination $aar
}
Run tar.exe @('-xf',$aar,'-C',"$build\vlc")
Run "$tools\aapt2.exe" @('compile','--dir',"$PSScriptRoot\res",'-o',"$build\resources.zip")
Run "$tools\aapt2.exe" @('link','-I',$android,'--manifest',"$PSScriptRoot\AndroidManifest.xml",'--java',"$build\generated",'--version-code','31','--version-name','1.30','-o',"$build\unsigned.apk","$build\resources.zip")
$sources = @(Get-ChildItem "$PSScriptRoot\src","$build\generated" -Recurse -Filter '*.java' | ForEach-Object FullName)


Copy-Item $android "$build\android.jar" -Force
Run javac (@('--release','8','-encoding','UTF-8','-classpath',"$build\android.jar;$build\vlc\classes.jar",'-d',"$build\classes")+$sources)
$classes = @(Get-ChildItem "$build\classes" -Recurse -Filter '*.class' | ForEach-Object FullName)
Run "$tools\d8.bat" (@('--min-api','26','--lib',$android,'--output',"$build\dex","$build\vlc\classes.jar")+$classes)
$package = "$build\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::Open($package,[IO.Compression.ZipArchiveMode]::Update)
try {
    foreach($dex in Get-ChildItem "$build\dex" -Filter '*.dex') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$dex.FullName,$dex.Name) | Out-Null
    }
    foreach($asset in Get-ChildItem "$build\vlc\assets" -Recurse -File) {
        $name=$asset.FullName.Substring("$build\vlc\assets".Length+1).Replace('\','/')
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$asset.FullName,"assets/$name") | Out-Null
    }
    foreach($library in Get-ChildItem "$build\vlc\jni" -Recurse -Filter '*.so') {
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$library.FullName,"lib/$($library.Directory.Name)/$($library.Name)") | Out-Null
    }
} finally { $zip.Dispose() }
Run "$tools\zipalign.exe" @('-f','-P','16','4',$package,"$build\aligned.apk")
if (!(Test-Path $SigningKey)) {
    Run keytool @('-genkeypair','-keystore',$SigningKey,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Island Development','-keyalg','RSA','-keysize','2048','-validity','10000')
}
Run "$tools\apksigner.bat" @('sign','--v4-signing-enabled','false','--ks',$SigningKey,'--ks-pass','pass:android','--out',$Output,"$build\aligned.apk")
Run "$tools\apksigner.bat" @('verify','--verbose',$Output)



















