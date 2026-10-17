# Rever para Android

Leitor de PDF com narração local pelo TTS do Android e exportação de áudio em MP3. A versão 1.0.0 foi preparada para Android 14. A biblioteca, os parágrafos, o ponto de leitura, as categorias e os áudios ficam no armazenamento interno do aplicativo.

## Biblioteca e leitura

- Importe PDFs com texto extraível, organize-os em uma ou mais categorias e alterne entre grade e lista.
- Ordene por nome com números em ordem natural, data de adição, última leitura ou total de páginas.
- Selecione vários PDFs com um toque longo e atribua categorias pela ação **Adicionar categoria**. A aba **Padrão** mostra PDFs sem categoria.
- Crie, renomeie, exclua e reordene categorias em **Configurações → Categorias**. Ao importar dentro de uma categoria, o PDF já recebe essa categoria.
- Arraste a bolinha lateral para navegar pelas páginas, toque na cápsula para digitar uma página e use pinça para ampliar em torno dos dedos.
- A reprodução continua em segundo plano. O player permite pausas, velocidade, saltos e navegação por parágrafo ou página. Exportações completas e por intervalo geram MP3.

PDFs digitalizados sem texto extraível e PDFs protegidos por senha ainda não são aceitos.

## Atualizações

Ao abrir o app, ele consulta a [última Release pública do Rever para Android](https://github.com/MichelSavadim/Rever-for-Android/releases). Se houver versão mais nova, exibe aviso no aplicativo e uma notificação, caso a permissão de notificações tenha sido concedida. **Pular esta versão** silencia os avisos daquela versão; ela continua acessível em **Configurações → Mensagens**. Uma versão posterior volta a gerar aviso.

Ao escolher **Atualizar**, o Rever baixa o APK da Release, verifica o tamanho, o SHA-256 quando fornecido pelo GitHub, o identificador do app, o número da versão e a assinatura. Depois abre o instalador do Android, que pede a confirmação do usuário. O Android pode exigir a autorização **Instalar apps desconhecidos** para o Rever. A primeira abertura após a atualização mostra o changelog. Em **Sobre → O que há de novo**, ele continua acessível.

O APK precisa ter uma assinatura aceita pelo Android para atualizar uma instalação existente. A versão 1.0.0 contém uma **linhagem de rotação** da chave de depuração usada nas prévias para a chave permanente. Em Android 14, isso permite atualizar diretamente a prévia assinada por aquela chave e preservar os dados internos. A prévia instalada deve ser uma das compilações feitas com a mesma chave de depuração; se o Android rejeitar a atualização, **não desinstale a prévia**, pois os dados internos seriam apagados. A migração ainda precisa ser confirmada no aparelho do usuário.

## Compilar

Requer Android Studio, SDK Android API 36 e Build Tools 36.0.0. Na pasta do projeto:

```powershell
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug lintDebug
```

O APK de teste fica em `app/build/outputs/apk/debug/app-debug.apk` e é assinado com a chave de depuração. Para a versão estável, execute `tools/build-release.ps1`: o script gera o APK sem assinatura, assina com `signing/rever-release.jks` e incorpora `signing/debug-to-release.lineage`. A senha fica em `signing/release-password.dpapi`, protegida para o usuário Windows que criou a chave. O APK assinado sai em `app/build/outputs/apk/release/app-release.apk`.

**Guarde um backup de `signing/rever-release.jks`, `signing/debug-to-release.lineage` e da senha em um gerenciador de senhas independente.** Para ver a senha neste computador e anotá-la com segurança, execute `tools/show-release-password.ps1` em um terminal privado. O arquivo `.dpapi` só é recuperável neste perfil Windows; copiá-lo sozinho para outro computador não basta. Sem a chave e a senha originais, versões futuras não poderão atualizar a mesma instalação. A pasta `signing/` e os arquivos `.jks`/`.keystore` são ignorados pelo Git.

## Verificações antes da publicação

- Instalar a 1.0.0 no Android 14 e testar rolagem, pinça, rotação e seleção com `Vol 1.pdf` (195 páginas), além de um PDF maior que antes causava crash.
- Instalar a 1.0.0 **sobre a prévia, sem desinstalá-la**, e conferir se PDFs, posição de leitura, parágrafos, áudios, voz e tema foram preservados.
- Criar e reordenar categorias; atribuir e remover categorias de um e de vários PDFs; importar dentro de uma categoria; excluir uma categoria e conferir a aba **Padrão**.
- Publicar um APK de teste de versão superior em uma Release e confirmar consulta, pular versão, notificação, download, assinatura, instalação e changelog após atualizar.
- Confirmar que a chave de lançamento foi guardada em backup. Sem ela, versões futuras não conseguem atualizar a mesma instalação.

Nenhum commit ou publicação é feito pelo aplicativo ou por este procedimento de compilação.
