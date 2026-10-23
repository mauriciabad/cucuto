// Spanish copy (Spain, informal "tú"). Same shape as en.ts; keep `{placeholders}` and markup identical.
import type { IconItem, Messages, QA } from './en.js';

const iconItems = (items: IconItem[]) => items;

export const es: Messages = {
	meta: {
		siteDescription:
			'Cucuto reproduce el sonido y la vibración de un personaje distinto para cada contacto de WhatsApp, para que sepas quién te escribe antes de mirar. Para Android 17 con root y LSPosed.',
		homeTitle: 'Cucuto: dale voz a cada contacto',
		ogImageAlt:
			'El logo de Cucuto: una casita de reloj de cuco mandarina con la puerta redonda abierta y un pájaro cantando.'
	},
	layout: {
		skipLink: 'Saltar al contenido',
		homeLabel: 'Inicio de Cucuto',
		navLabel: 'Principal',
		nav: { packs: 'Packs', install: 'Instalar', authors: 'Crear packs', privacy: 'Privacidad' },
		github: 'GitHub',
		languageLabel: 'Idioma',
		switchTo: 'Read in English',
		footerLicenseHtml:
			'<strong>Cucuto</strong> es software libre con licencia <a href="{license}" rel="external">GPL-3.0-or-later</a>.',
		footerContent:
			'La app no incluye contenido de terceros; tú eres responsable de los packs que importas. Sin relación con WhatsApp ni Meta.',
		footerLinks: {
			source: 'Código en GitHub',
			install: 'Guía de instalación',
			privacy: 'Privacidad'
		}
	},
	download: {
		button: 'Descargar APK',
		comingSoon: 'APK muy pronto',
		metaHtml:
			'Versión {version} · {size} · <a href="{notes}" rel="external">notas de la versión</a>',
		soonHtml:
			'Cucuto está en desarrollo y la primera versión está en camino. Síguela en la <a href="{releases}" rel="external">página de versiones</a>.'
	},
	clock: {
		replay: 'Volver a reproducir la animación del reloj de cuco',
		still: 'Un reloj de cuco con la puerta redonda abierta y un pájaro cantando'
	},
	home: {
		eyebrow: 'Un módulo de LSPosed para Android 17',
		pitch: 'Dale voz a cada contacto.',
		lead: 'Cucuto reproduce el sonido y la vibración de un personaje distinto para cada contacto de WhatsApp, como un reloj de cuco que anuncia quién llama. Un gato para tu hermana, una trompeta para tu jefa.',
		howToInstall: 'Cómo instalarlo',
		finePrintHtml:
			'Gratis y de <a href="{repo}" rel="external">código abierto</a> · Necesita root · Sin rastreo',
		videoTitle: 'Míralo en acción',
		videoSoonTitle: 'El vídeo explicativo está en camino',
		videoSoonText:
			'Un recorrido corto: llega un mensaje, el móvil reproduce el personaje de quien lo envía y sabes quién es sin mirar.',
		howTitle: 'Cómo funciona',
		steps: iconItems([
			{
				icon: 'library-music',
				title: 'Importa un pack de sonidos',
				text: 'Abre un archivo .soundpack.zip o descárgalo de un repositorio de packs. Un pack es un reparto de personajes, cada uno con su imagen, unos cuantos clips cortos y su propia vibración.'
			},
			{
				icon: 'person-pin',
				title: 'Dale un personaje a un contacto',
				text: 'Elige un chat, toca un personaje para oírlo y vuelve a tocarlo para asignarlo. Son unos segundos por contacto.'
			},
			{
				icon: 'notifications-active',
				title: 'Oye quién es',
				text: 'Cuando te escriba, la notificación de WhatsApp sonará y vibrará con su personaje. Con el móvil en el bolsillo, ya sabes quién es.'
			}
		]),
		featuresTitle: 'Hecho para no molestar',
		featuresLead:
			'Cucuto cambia una sola cosa, el sonido y la vibración de la propia notificación de WhatsApp, y deja todo lo demás en manos de Android.',
		features: iconItems([
			{
				icon: 'notifications-active',
				title: 'Un sonido, sin duplicados',
				text: 'Ni una segunda notificación ni un icono fijo en la barra de estado. El aviso que recibes es el de WhatsApp, con otra voz.'
			},
			{
				icon: 'do-not-disturb',
				title: 'Tus ajustes mandan',
				text: 'Los chats silenciados, No molestar y el modo silencio funcionan igual que siempre, porque Android sigue decidiendo si un mensaje avisa.'
			},
			{
				icon: 'mobile-vibrate',
				title: 'Una vibración por personaje',
				text: 'Cada personaje puede traer su propio patrón de vibración, así sabes quién escribe incluso con el móvil en vibración.'
			},
			{
				icon: 'shield',
				title: 'Si algo falla, suena lo de siempre',
				text: 'Si el módulo está desactivado o algo falla, suena el tono normal de WhatsApp. Nunca te pierdes un mensaje por culpa de Cucuto.'
			},
			{
				icon: 'groups',
				title: 'También en grupos',
				text: 'Dale a un grupo un personaje propio, o deja que cada persona del grupo hable con el suyo.'
			},
			{
				icon: 'power-settings',
				title: 'Nada en segundo plano',
				text: 'Sin servicio en segundo plano, sin notificación permanente y sin excepciones de batería.'
			}
		]),
		screenshotsTitle: 'Un vistazo por dentro',
		screenshotsLead:
			'Las capturas de la app con los packs de demostración gratuitos llegarán con la primera versión.',
		screenshotSoon: 'Captura muy pronto',
		screenshots: [
			{ title: 'Estado', text: 'Una tarjeta que se pone verde cuando todo está listo.' },
			{ title: 'Chats', text: 'Quienes te han escrito hace poco, esperando una voz.' },
			{
				title: 'Personajes',
				text: 'Toca un personaje para oírlo y vuelve a tocarlo para asignarlo.'
			},
			{ title: 'Packs', text: 'Explora repositorios, escucha los clips y descarga packs.' }
		],
		requirementsTitle: 'Requisitos',
		requirementsLead:
			'Cucuto es una herramienta para usuarios avanzados. Comprueba que tu móvil cumple todo esto antes de instalarlo.',
		requirements: iconItems([
			{
				icon: 'android',
				title: 'Android 17',
				text: 'Cucuto está hecho solo para Android 17 (API 37). Las versiones anteriores no son compatibles.'
			},
			{
				icon: 'lock',
				title: 'Acceso root',
				text: 'El root permite que el módulo cambie la notificación de WhatsApp desde dentro del propio Android.'
			},
			{
				icon: 'extension',
				title: 'LSPosed con libxposed API 102',
				text: 'El módulo funciona en el ámbito System Framework. Nunca engancha la app de WhatsApp.'
			},
			{
				icon: 'notifications-active',
				title: 'WhatsApp o WhatsApp Business',
				text: 'Deja activados el tono y la vibración de WhatsApp: Cucuto los cambia por contacto.'
			}
		]),
		faqTitle: 'Preguntas',
		faq: [
			{
				q: '¿Cucuto incluye sonidos de series o videojuegos?',
				aHtml:
					'No. La app viene vacía y no incluye contenido con derechos de autor. Tú importas los packs de sonidos; el <a href="{packs}">repositorio oficial</a> solo aloja packs de demostración gratuitos (CC0). Eres responsable de los packs que importas.'
			},
			{
				q: '¿Por qué necesita root?',
				aHtml:
					'En Android 17 una app normal no puede reproducir sonidos en segundo plano de forma fiable, y copiar las notificaciones de WhatsApp supondría duplicados y desorden. Con root, Cucuto cambia el sonido y la vibración de la propia notificación de WhatsApp, así que silenciar, No molestar y el modo de sonido siguen funcionando como siempre.'
			},
			{
				q: '¿Modifica WhatsApp?',
				aHtml:
					'No. El módulo solo funciona dentro del proceso del sistema de Android (el ámbito System Framework) y nunca toca la app de WhatsApp, así que convive con otros módulos que sí enganchan WhatsApp.'
			},
			{
				q: '¿Qué pasa si algo falla?',
				aHtml:
					'Oyes el tono normal de WhatsApp. Si el módulo está desactivado o algo falla, Android reproduce el sonido predeterminado en lugar de quedarse en silencio.'
			},
			{
				q: '¿Funciona en los grupos?',
				aHtml:
					'Sí. Por defecto, cada grupo tiene un personaje propio. También puedes hacer que un grupo suene con el personaje de quien escribe.'
			},
			{
				q: '¿Y las llamadas?',
				aHtml:
					'Está previsto añadir tonos de llamada por contacto con los mismos packs. Cucuto empieza por las notificaciones de mensajes de WhatsApp.'
			},
			{
				q: '¿Puedo crear mis propios packs?',
				aHtml:
					'Sí. El formato de los packs es abierto y está documentado, y cualquiera puede alojar un repositorio de packs en una web estática. Consulta la <a href="{authors}">guía para crear packs</a>. Está previsto un creador de packs en la app y en esta web.'
			},
			{
				q: '¿Por qué no está en Google Play?',
				aHtml:
					'Cucuto necesita root y LSPosed, así que se publica como APK en <a href="{releases}" rel="external">GitHub</a>.'
			},
			{
				q: '¿Es gratis? ¿Recoge datos?',
				aHtml:
					'Cucuto es gratis y de código abierto con licencia GPL-3.0-or-later. No tiene cuentas, ni anuncios, ni analíticas, y esta web tampoco. Más detalles en la <a href="{privacy}">página de privacidad</a>.'
			}
		] satisfies QA[] as QA[]
	},
	packs: {
		title: 'Packs',
		description:
			'Packs de sonidos gratuitos para Cucuto: personajes con clips y vibraciones, listos para importar.',
		lead: 'Un pack es un reparto de personajes, cada uno con su imagen, clips cortos y una vibración. Los packs gratuitos de esta lista son CC0: puedes usarlos, cambiarlos y compartirlos.',
		repoNoteHtml:
			'Esta lista también es el repositorio de packs predeterminado de la app, <code>{path}</code>: explora y descarga los mismos packs desde la app, o toca <strong>Añadir a la app</strong> para volver a añadirlo.',
		bundle: 'Colección',
		unknownAuthor: 'Autor desconocido',
		noLicense: 'Sin licencia',
		characters: 'Personajes',
		play: 'Reproducir el clip de {name}',
		stop: 'Parar el clip de {name}',
		contains: 'Incluye {names}.',
		download: 'Descargar',
		downloadHidden: '{name} .soundpack.zip',
		addToApp: 'Añadir a la app',
		placeholders: ['Animales', 'Chiptune', 'Campanitas'],
		soonTitle: 'Los primeros packs gratuitos están en camino',
		soonTextHtml:
			'Cuando se publiquen los packs de demostración, esta galería mostrará cada pack con sus personajes, clips de muestra y un botón de descarga. La misma lista estará disponible para la app en <code>{path}</code>, el repositorio de packs predeterminado.',
		soonAuthorsHtml: '¿Quieres crear uno? Lee la <a href="{authors}">guía para crear packs</a>.'
	},
	install: {
		title: 'Guía de instalación',
		description:
			'Instala Cucuto en un móvil con Android 17 y root: APK, módulo de LSPosed con ámbito System Framework, reinicio y comprobaciones de estado.',
		lead: 'Cinco pasos, unos cinco minutos. Necesitas Android 17, root y LSPosed con libxposed API 102 ya funcionando en tu móvil.',
		preReleaseHtml:
			'<strong>Cucuto aún no ha publicado su primera versión.</strong> Esta guía describe la instalación de la versión 1. El botón de descarga aparecerá aquí en cuanto salga; síguela en la <a href="{releases}" rel="external">página de versiones</a>.',
		beforeTitle: 'Antes de empezar',
		before: iconItems([
			{
				icon: 'android',
				title: 'Android 17',
				text: 'En Ajustes › Información del teléfono ves la versión de Android. Cucuto no funciona en Android 16 ni anteriores.'
			},
			{
				icon: 'lock',
				title: 'Root',
				text: 'Tu móvil tiene root (por ejemplo con Magisk, KernelSU o APatch). Cucuto no rootea el móvil por ti.'
			},
			{
				icon: 'extension',
				title: 'LSPosed',
				text: 'Tienes instalada y funcionando una versión de LSPosed que carga módulos modernos (libxposed API 102).'
			},
			{
				icon: 'notifications-active',
				title: 'WhatsApp',
				text: 'WhatsApp o WhatsApp Business, con las notificaciones activadas y su propio tono y vibración activados.'
			}
		]),
		stepsTitle: 'Pasos',
		appIconAlt: 'El icono de la app Cucuto',
		steps: [
			{
				title: 'Descarga e instala el APK',
				textHtml:
					'Descarga el último <code>.apk</code> de la <a href="{releaseUrl}" rel="external">página de la versión en GitHub</a> y ábrelo en el móvil. La primera vez, Android te pide permiso para instalar apps desde esa app (el navegador o el gestor de archivos). Una vez instalado, busca este icono.'
			},
			{
				title: 'Activa el módulo en LSPosed',
				textHtml:
					'Abre el gestor de LSPosed, ve a <strong>Módulos</strong>, activa <strong>Cucuto</strong> y comprueba que su ámbito es solo <strong>System Framework</strong>. Cucuto pide exactamente ese ámbito, así que normalmente ya está marcado. No añadas WhatsApp al ámbito: Cucuto nunca engancha la app de WhatsApp.'
			},
			{
				title: 'Reinicia',
				textHtml:
					'El módulo se carga en el proceso del sistema de Android al arrancar, así que hace falta reiniciar del todo después de activarlo, de cambiar su ámbito o de actualizar Cucuto.'
			},
			{
				title: 'Revisa la tarjeta de estado',
				textHtml:
					'Abre Cucuto. La primera vez, una guía de configuración te acompaña en estos pasos y termina con una notificación de prueba; puedes volver a abrirla desde la pantalla de estado. La tarjeta de estado de arriba comprueba que el módulo está activo, que el ámbito de LSPosed es correcto, que WhatsApp está instalado y que hay al menos un pack importado. Cada comprobación que falla te dice cómo arreglarla.'
			},
			{
				title: 'Importa un pack y asigna personajes',
				textHtml:
					'Importa un archivo <code>.soundpack.zip</code> o elige uno de los <a href="{packs}">packs gratuitos</a>, luego escoge un chat y toca un personaje. Deja activados el tono y la vibración de las notificaciones de WhatsApp: Cucuto los sustituye por contacto.'
			}
		],
		testTitle: 'Pruébalo',
		testHtml:
			'En Cucuto, abre <strong>Diagnóstico</strong> y toca <strong>Enviar notificación de prueba</strong>. Con el módulo activo oirás un gorjeo corto de dos tonos y notarás un clic en lugar del sonido predeterminado, y la decisión aparecerá en la lista de abajo en unos segundos.',
		troubleTitle: 'Solución de problemas',
		trouble: [
			{
				q: 'El módulo aparece como inactivo.',
				aHtml:
					'Comprueba en LSPosed que Cucuto está activado con el ámbito System Framework y reinicia. Los registros de LSPosed muestran si el módulo se ha cargado.'
			},
			{
				q: 'Un contacto suena con el tono normal de WhatsApp.',
				aHtml:
					'Es el comportamiento seguro: el contacto aún no tiene personaje, el interruptor de voces está apagado o el módulo no está activo. El registro de decisiones de <strong>Diagnóstico</strong> muestra qué pasó con cada notificación reciente.'
			},
			{
				q: 'No suena nada.',
				aHtml:
					'Cucuto solo sustituye un sonido que iba a sonar. Comprueba que el chat no está silenciado, que No molestar está desactivado y que el tono de notificación de WhatsApp no está en silencio.'
			},
			{
				q: 'El móvil no arranca después de activar el módulo.',
				aHtml:
					'Arranca en el modo seguro de tu gestor de root (normalmente pulsando Bajar volumen durante el arranque; consulta su documentación). Desactiva todos los módulos, LSPosed incluido. Después desinstala Cucuto y reinicia con normalidad: LSPosed descarta un módulo cuya app ya no está. Hay más formas de recuperarlo en la <a href="{repo}/blob/main/docs/install.md#if-the-phone-does-not-boot" rel="external">guía de instalación en GitHub</a> (en inglés).'
			},
			{
				q: '¿Sigues atascado?',
				aHtml:
					'<a href="{issues}" rel="external">Abre una incidencia en GitHub</a> con tu versión de Android, tu versión de LSPosed y lo que muestra Diagnóstico.'
			}
		] satisfies QA[] as QA[],
		removeTitle: 'Desactivar o desinstalar',
		removeHtml:
			'Para pausar Cucuto, apaga el interruptor de voces en la app: todos los chats vuelven al tono de WhatsApp al momento. Para quitarlo del todo, desactiva el módulo en LSPosed, reinicia y desinstala la app. Al desinstalarla se borran los packs importados y las asignaciones.'
	},
	authors: {
		title: 'Crear packs',
		description:
			'Crea packs de sonidos para Cucuto: el formato abierto .soundpack.zip (un zip normal), la herramienta packer y cómo alojar tu propio repositorio de packs.',
		lead: 'Cualquiera puede crear un pack: animales, sonidos de videojuegos, la voz de un amigo, una canción. El formato es abierto, así que un pack hecho en cualquier sitio se importa en todas partes.',
		toc: 'En esta página',
		whatTitle: 'Qué lleva un pack',
		whatHtml:
			'Un pack es un archivo zip normal llamado <code>&lt;nombre&gt;.soundpack.zip</code>, con un manifiesto <code>pack.json</code> en la raíz y una carpeta por personaje. Cada personaje tiene una imagen, un conjunto de clips cortos para las notificaciones y, si quieres, un patrón de vibración y tonos de llamada. Un archivo también puede contener una <strong>colección</strong> de varios packs.',
		mediaTitle: 'Archivos',
		media: iconItems([
			{
				icon: 'library-music',
				title: 'Clips',
				text: 'Sonidos cortos, de 0,2 a 4 segundos cada uno. Se recomienda Ogg Opus; también valen Ogg Vorbis, MP3, AAC, FLAC y WAV.'
			},
			{
				icon: 'person-pin',
				title: 'Imágenes',
				text: 'Se recomienda JPEG XL; también valen PNG, JPEG, WebP, AVIF, GIF y SVG, de 512 px por lado como máximo. Una imagen animada (JPEG XL, WebP o GIF) es opcional.'
			},
			{
				icon: 'mobile-vibrate',
				title: 'Vibración',
				text: 'Automática (sigue el volumen del clip), ninguna, o tu propia secuencia de toques y ondas.'
			}
		]),
		mediaNoteHtml:
			'El <a href="{packFormat}">formato de los packs</a> detalla los límites exactos de duración, tamaño y tipos de archivo.',
		makeTitle: 'Tres formas de crear uno',
		make: [
			{
				title: 'Con packer',
				textHtml:
					'Describe tu pack en un archivo <code>pack.yaml</code> y deja que la herramienta de línea de comandos lo construya, lo valide y lo agrupe. Disponible ya.'
			},
			{
				title: 'Con el creador de packs',
				textHtml:
					'Elige imágenes, graba o importa clips, recórtalos y exporta, en la app o en esta web, sin herramientas. <strong>Previsto.</strong>'
			},
			{
				title: 'A mano',
				textHtml:
					'Escribe tú el <code>pack.json</code> y comprime la carpeta en zip. Sigue el <a href="{packFormat}">formato de los packs</a> al pie de la letra.'
			}
		],
		packerTitle: 'Constrúyelo con packer',
		packerIntroHtml:
			'<code>packer</code> convierte una carpeta de clips e imágenes en un <code>.soundpack.zip</code> y lo valida con las mismas reglas que la app, así que un pack que pasa se importa en todas partes. Necesita Java 21; compílalo desde el repositorio:',
		packerYamlHtml:
			'Pon tus archivos en una carpeta con un <code>pack.yaml</code> al lado. Las carpetas y los comodines recogen todos los clips:',
		packerCommandsHtml:
			'Luego constrúyelo, valídalo y, si quieres, agrupa varios packs en un solo archivo. Hay opciones para normalizar el volumen, recortar silencios y convertir a Opus y JPEG XL (necesitan <code>ffmpeg</code> y <code>cjxl</code>):',
		packerMoreHtml:
			'Todos los comandos y opciones están en el <a href="{packerReadme}" rel="external">README de packer</a> (en inglés).',
		hostTitle: 'Aloja tu propio repositorio',
		hostIntroHtml:
			'Un repositorio de packs es cualquier ubicación HTTPS que sirva un índice <code>repository.json</code> y los archivos que lista: GitHub Pages, Cloudflare Pages, un bucket o tu propio servidor. Sin código de servidor. <code>packer index</code> genera una carpeta lista para subir con el índice, vistas previas pequeñas y los packs:',
		hostShareHtml:
			'Sube la carpeta y comparte su URL. La gente la pega en la app (o la escanea como código QR), explora tus packs, escucha los clips y descarga los que le gusten. En una web, un enlace <strong>Añadir a la app</strong> lo hace con un toque:',
		hostPrivateHtml:
			'La app comprueba el tamaño y el SHA-256 de cada descarga. Esta web es el repositorio predeterminado, en <code>{path}</code>.',
		rightsTitle: 'Comparte solo lo que puedes compartir',
		rightsHtml:
			'Publica solo clips e imágenes que hayas hecho tú o sobre los que tengas derechos, y rellena el campo <code>license</code>. El repositorio oficial solo aloja packs CC0. Los packs personales con contenido con derechos de autor pueden quedarse en privado: impórtalos directamente desde un archivo en tu móvil.',
		specTitle: 'La especificación completa',
		specHtml:
			'Cada campo, límite, regla de validación y código de error está en el formato de los packs, versión 1. Los JSON Schemas y los packs de prueba del repositorio son el contrato que la app, packer y esta web comprueban.',
		specButton: 'Leer el formato de los packs'
	},
	packFormat: {
		title: 'Formato de los packs',
		description:
			'El formato de packs de Cucuto, versión 1: pack.json, colecciones, reglas de archivos y del zip, validación y repositorios de packs.',
		back: 'Crear packs',
		sourceHtml:
			'Esta página se genera a partir de <a href="{packFormatSource}" rel="external"><code>docs/pack-format.md</code></a> en el repositorio, donde también puedes proponer cambios.',
		englishOnly:
			'La especificación técnica solo está en inglés, para que haya una única referencia exacta.',
		toc: 'Contenido'
	},
	privacy: {
		title: 'Privacidad',
		description:
			'La web de Cucuto no tiene analíticas, ni cookies, ni rastreo. La app no tiene cuentas ni analíticas, y tus datos se quedan en tu móvil.',
		lead: 'En resumen: no recogemos nada. Ni en esta web ni en la app.',
		updated: 'Última actualización: {date}',
		promises: iconItems([
			{
				icon: 'block',
				title: 'Sin analíticas',
				text: 'Sin contadores, píxeles ni grabaciones de sesión.'
			},
			{ icon: 'cookie', title: 'Sin cookies', text: 'No se guarda nada para reconocerte.' },
			{
				icon: 'no-accounts',
				title: 'Sin cuentas',
				text: 'Nada en lo que registrarse, sin perfiles, sin anuncios.'
			},
			{
				icon: 'visibility-off',
				title: 'Sin leer mensajes',
				text: 'Cucuto nunca lee lo que dicen tus mensajes.'
			}
		]),
		siteTitle: 'Esta web',
		site: [
			'Todas las páginas, fuentes, imágenes y clips de muestra se sirven desde esta web; no hace peticiones a otros servicios. Para funcionar sin conexión y cargar más rápido, tu navegador guarda una copia de la web en su caché (un service worker). Esa copia se queda en tu dispositivo y puedes borrarla en los ajustes del navegador.',
			'La web está alojada en GitHub Pages, y el APK y los packs se descargan como archivos de versiones de GitHub. Como cualquier alojamiento web, GitHub recibe datos técnicos como tu dirección IP al servir un archivo; consulta la <a href="https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement" rel="external">declaración de privacidad de GitHub</a>.'
		],
		appTitle: 'La app',
		appLeadHtml:
			'Cucuto no tiene cuentas, ni anuncios, ni analíticas, ni informes de errores automáticos. Todo lo que sabe se queda en su almacenamiento privado en tu móvil.',
		storedTitle: 'Qué se queda en tu móvil',
		stored: [
			'<strong>Tus packs</strong>: los archivos de packs que importas o descargas.',
			'<strong>Tus asignaciones</strong>: qué personaje habla por cada chat, y tus ajustes.',
			'<strong>Chats recientes</strong>: cuando llega una notificación de WhatsApp, el módulo anota el nombre del chat, el nombre de quien escribe y el identificador interno de WhatsApp para ese chat, para que la gente nueva aparezca en la app lista para recibir una voz. Guarda los 1000 chats más recientes.',
			'<strong>El registro de decisiones</strong>: qué hizo Cucuto con cada notificación reciente (qué voz sonó, o por qué sonó el tono de WhatsApp). Guarda las 500 entradas más recientes, para que puedas ver por qué sonó algo.'
		],
		storedNoteHtml:
			'El módulo nunca lee el texto de los mensajes ni escribe nombres o identificadores de chat en los registros del sistema. Al desinstalar la app se borra todo.',
		moduleTitle: 'Cómo funciona el módulo',
		moduleHtml:
			'El módulo funciona dentro del proceso del sistema de Android, donde se gestionan las notificaciones, y solo mira las notificaciones de WhatsApp y WhatsApp Business. Lo que anota se lo pasa a la app por un canal privado que rechaza a cualquier otra app. Nunca se conecta a internet y nunca toca la app de WhatsApp.',
		networkTitle: 'Cuándo se conecta la app',
		networkHtml:
			'Solo para acceder a los repositorios de packs de tu lista (el de esta web viene por defecto): para mostrar sus packs, buscar actualizaciones y descargar los packs que elijas. Las descargas pueden venir del servidor del propio repositorio, como GitHub en el caso de los packs de esta web. Si un repositorio privado necesita contraseña o token, se guarda cifrado con el almacén de claves de Android y solo se envía al servidor de ese repositorio.',
		backupTitle: 'Copias de seguridad',
		backupHtml:
			'La copia de seguridad en la nube de Android y la transferencia entre dispositivos están desactivadas para Cucuto, así que no se copia nada a Google. Está prevista una copia de seguridad propia de Cucuto, en un archivo que guardas donde quieras.',
		permissionsTitle: 'Permisos',
		permissions: [
			'<strong>Internet</strong>: para los repositorios de packs de arriba.',
			'<strong>Notificaciones</strong>: solo para la notificación de prueba de Diagnóstico.'
		],
		permissionsNoteHtml:
			'Sin acceso a tus contactos, archivos, ubicación, cámara ni micrófono. Si una función futura necesita más (por ejemplo el micrófono para el creador de packs previsto), la app lo pedirá antes y esta página explicará por qué.',
		checkTitle: 'Compruébalo tú',
		checkHtml:
			'La web y la app son de código abierto: lee el código en <a href="{repo}" rel="external">GitHub</a>. ¿Dudas sobre privacidad? <a href="{issues}" rel="external">Abre una incidencia</a>.'
	},
	open: {
		title: 'Abrir un pack',
		description: 'Abre archivos .soundpack.zip con la web app de Cucuto.',
		leadHtml:
			'Abrir archivos de packs (<code>.soundpack.zip</code>, o cualquier <code>.zip</code> de pack) aquí llegará con el creador de packs. De momento, importa los packs en la app Cucuto de tu móvil.',
		opened: 'Has abierto:',
		browse: 'Ver packs gratuitos'
	},
	error: {
		notFoundTitle: 'Página no encontrada',
		otherTitle: 'Algo ha fallado',
		notFoundHeading: 'No hay nadie en esta dirección',
		notFoundText: 'Esta página no existe. Revisa la dirección o empieza desde la página de inicio.',
		otherText: 'La página no ha podido cargar ({status}). Vuelve a intentarlo en un momento.',
		home: 'Ir a la página de inicio'
	}
};
