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
		nav: {
			packs: 'Packs',
			create: 'Crear',
			install: 'Instalar',
			authors: 'Para autores',
			privacy: 'Privacidad'
		},
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
		video: {
			soonBadge: 'Próximamente',
			posterAlt:
				'Ilustración: un móvil muestra el selector de personajes de Cucuto, donde se asigna la rana Bleep entre una lluvia de confeti. Al lado, el reloj de cuco canta y tres chats tienen cada uno su personaje.',
			label: 'Vídeo explicativo de Cucuto',
			play: 'Reproducir el vídeo',
			unsupported:
				'Tu navegador no puede reproducir este vídeo. La transcripción de abajo recoge todo lo que dice.',
			transcript: 'Leer la transcripción'
		},
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
			'La app con los dos packs de demostración gratuitos y chats inventados, en tu tema claro u oscuro.',
		screenshots: [
			{
				image: 'onboarding',
				title: 'Configuración',
				text: 'Una configuración guiada y breve comprueba el módulo y su ámbito por ti.',
				alt: 'La pantalla de bienvenida de Cucuto: el reloj de cuco con la puerta abierta y lo que hace la app.'
			},
			{
				image: 'home',
				title: 'Chats',
				text: 'Quienes te han escrito hace poco, esperando una voz.',
				alt: 'La pantalla de inicio: quienes te han escrito sin voz y los chats con sus personajes.'
			},
			{
				image: 'picker',
				title: 'Personajes',
				text: 'Toca un personaje para oírlo y vuelve a tocarlo para asignarlo.',
				alt: 'El selector de personajes de un chat, con los personajes de los packs de demostración por pack.'
			},
			{
				image: 'packs',
				title: 'Packs',
				text: 'Tus packs y sus personajes. Toca uno para oírlo.',
				alt: 'La pantalla Packs con los packs de demostración Chiptune critters y Kitchen orchestra.'
			}
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
					'Sí. El formato de los packs es abierto y está documentado, y cualquiera puede alojar un repositorio de packs en una web estática. Consulta la <a href="{authors}">guía para crear packs</a>. Crea uno en el navegador con la página <a href="{create}">Crear</a>; está previsto un creador de packs en la app.'
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
		updatesTitle: 'Actualizaciones',
		updatesHtml:
			'Cucuto nunca busca actualizaciones ni se conecta a internet para buscarlas. Las versiones nuevas aparecen en la <a href="{releases}" rel="external">página de versiones</a>, y Cucuto sigue funcionando aunque te saltes alguna. Para recibirlas en el móvil sin mirar a mano, usa <a href="{obtainium}" rel="external">Obtainium</a>, una app gratuita que instala apps directamente desde sus versiones en GitHub. Después de cada actualización, reinicia: el módulo carga la versión nueva al arrancar.',
		obtainiumButton: 'Consíguelo con Obtainium',
		obtainiumHint:
			'Abre Obtainium con los ajustes de Cucuto ya rellenados. Funciona en un móvil con Obtainium instalado.',
		obtainiumManualTitle: 'Configurar Obtainium a mano',
		obtainiumManualHtml:
			'En Obtainium, toca <strong>Añadir aplicación</strong>, pega la URL de origen y abre las opciones adicionales para rellenar el resto. El filtro de títulos se salta las versiones de los packs de demostración, y el certificado hace que Obtainium rechace un APK firmado por otra persona.',
		// Obtainium's own labels, verbatim, so readers find them in its UI (its Spanish translation leaves some in English).
		obtainiumFields: {
			source: 'URL origen de la aplicación',
			apkFilter: 'Filter links by regular expression',
			titleFilter: 'Filtrar por título de versión',
			cert: 'Expected signing certificate hashes'
		},
		obtainiumJson: 'Los mismos ajustes como configuración de app de Obtainium (JSON):',
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
				text: 'Sonidos de 0,2 a 30 segundos cada uno; los cortos funcionan mejor. Se recomienda Ogg Opus; también valen Ogg Vorbis, MP3, AAC, FLAC y WAV.'
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
					'Elige imágenes, graba o importa clips, recórtalos y descarga el pack en la página <a href="{create}">Crear</a>, en el navegador y sin herramientas. En la app: previsto.'
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
		checkTitle: 'Comprueba un pack en el navegador',
		checkHtml:
			'¿No tienes Java a mano? Elige aquí un archivo de pack. Esta página aplica las mismas reglas que la app y <code>packer</code> (el mismo código, compilado para la web) y da los mismos códigos. El archivo no sale de tu dispositivo: no se sube nada.',
		hostTitle: 'Aloja tu propio repositorio',
		hostIntroHtml:
			'Un repositorio de packs es cualquier ubicación HTTPS que sirva un índice <code>repository.json</code> y los archivos que lista: GitHub Pages, Cloudflare Pages, un bucket o tu propio servidor. Sin código de servidor. <code>packer index</code> genera una carpeta lista para subir con el índice, vistas previas pequeñas y los packs (¿sin Java? <a href="{createRepository}">crea la carpeta en el navegador</a>):',
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
			'La página <a href="{create}">Crear</a> usa tu micrófono solo mientras grabas, y solo si lo permites. Las imágenes y los sonidos que añades allí se procesan en tu navegador y nunca salen de tu dispositivo. Los borradores se guardan en el almacenamiento del navegador de este dispositivo hasta que los borres o borres los datos de la web.',
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
			'El módulo nunca lee el texto de los mensajes ni escribe nombres o identificadores de chat en los registros del sistema. Cucuto nunca abre las bases de datos, los chats ni los archivos multimedia de WhatsApp: solo conoce un chat por sus notificaciones o por un contacto que vinculas o añades tú. Al desinstalar la app se borra todo.',
		moduleTitle: 'Cómo funciona el módulo',
		moduleHtml:
			'El módulo funciona dentro del proceso del sistema de Android, donde se gestionan las notificaciones, y solo mira las notificaciones de WhatsApp y WhatsApp Business. Lo que anota se lo pasa a la app por un canal privado que rechaza a cualquier otra app. Nunca se conecta a internet y nunca toca la app de WhatsApp.',
		networkTitle: 'Cuándo se conecta la app',
		networkHtml:
			'Solo para acceder a los repositorios de packs de tu lista (el de esta web viene por defecto): para mostrar sus packs, buscar actualizaciones y descargar los packs que elijas. Las descargas pueden venir del servidor del propio repositorio, como GitHub en el caso de los packs de esta web. Si un repositorio privado necesita contraseña o token, se guarda cifrado con el almacén de claves de Android y solo se envía al servidor de ese repositorio.',
		backupTitle: 'Copias de seguridad',
		backup: [
			'Cucuto puede guardar su propia copia de seguridad en un archivo, para que un móvil nuevo lo recupere todo. Incluye los chats que Cucuto conoce (nombres, números de teléfono, los identificadores de chat de WhatsApp y el contacto con el que está vinculado cada uno), sus voces y tonos de llamada, el interruptor de voces, tu lista de repositorios y los packs que usas, y también los archivos de los packs, salvo que los dejes fuera.',
			'Nunca va en una copia: tu ubicación para el día y la noche, ni las contraseñas o tokens de los repositorios, salvo que los añadas a una copia que protejas con una contraseña tuya. Entonces todo el archivo va cifrado. Las copias automáticas nunca se cifran, así que nunca llevan contraseñas.',
			'Las copias van solo adonde tú elijas: un archivo que guardas a mano, o una carpeta o un archivo que eliges para las copias automáticas. Si eliges Google Drive, es la app de Drive la que sube el archivo; Cucuto nunca sube nada.',
			'La copia de seguridad en la nube de Android y la transferencia entre dispositivos están desactivadas para Cucuto, así que por esa vía no se copia nada a Google.'
		],
		permissionsTitle: 'Permisos',
		permissions: [
			'<strong>Internet</strong>: para los repositorios de packs de arriba.',
			'<strong>Notificaciones</strong>: solo para la notificación de prueba de Diagnóstico.',
			'<strong>Contactos</strong>: solo si activas los tonos de llamada, para vincular un chat con uno de tus contactos y ponerle su tono, el mismo ajuste que cambia la app Contactos. Y cuando tocas «Añadir un chat desde contactos»: Cucuto lee las entradas de WhatsApp que ya tienen tus contactos (cada una, un nombre y un número de WhatsApp) y guarda solo a las personas que eliges. Cuando una de ellas te escribe por primera vez, vuelve a comparar ese nombre con esas entradas, para no vincular nunca a otra persona con el mismo nombre. No lee nada más de tus contactos.',
			'<strong>Teléfono</strong>: solo para los tonos «Al azar en cada llamada», para saber cuándo termina una llamada. Nunca se lee el número.',
			'<strong>Ubicación aproximada</strong>: una sola lectura, solo cuando tocas «Usar mi ubicación una vez» en «Día y noche». Nunca la ubicación exacta, nunca en segundo plano.',
			'<strong>Micrófono</strong>: solo mientras grabas un sonido en el creador de packs.'
		],
		permissionsNoteHtml:
			'Contactos, teléfono, ubicación y micrófono se piden solo cuando usas la función que los necesita, y todo lo demás sigue funcionando si dices que no. Sin acceso a tu cámara, a tus archivos (eliges tú cada archivo que abres o guardas) ni a los datos de WhatsApp. Si una función futura necesita más, la app lo pedirá antes y esta página explicará por qué.',
		checkTitle: 'Compruébalo tú',
		checkHtml:
			'La web y la app son de código abierto: lee el código en <a href="{repo}" rel="external">GitHub</a>. ¿Dudas sobre privacidad? <a href="{issues}" rel="external">Abre una incidencia</a>.'
	},
	open: {
		title: 'Abrir un pack',
		description: 'Abre archivos .soundpack.zip con la web app de Cucuto.',
		leadHtml:
			'Cucuto comprueba los archivos de pack que abras aquí (<code>.soundpack.zip</code>, o cualquier <code>.zip</code> de pack) con las mismas reglas que la app, en tu dispositivo. Para hacer el tuyo, usa la página <a href="{create}">Crear</a>; para instalar un pack, impórtalo en la app Cucuto de tu móvil.',
		opened: 'Has abierto:',
		browse: 'Ver packs gratuitos',
		edit: 'Editar en el creador de packs',
		editFailed: 'No se ha podido pasar el pack al creador ({error}).'
	},
	packCheck: {
		choose: 'Elegir archivos de pack',
		checking: 'Comprobando…',
		valid: 'Listo para importar',
		validWithWarnings: 'Listo para importar, con avisos',
		invalid: 'La app no importaría este archivo',
		bundle: 'Una colección de {count} packs:',
		pack: '{version} de {author}, personajes: {count}',
		error: 'error',
		warning: 'aviso',
		codesHtml:
			'Cada código se explica en el <a href="{packFormat}#error-codes">formato de pack</a>. Los detalles están en inglés.',
		failed: 'No se ha podido comprobar el archivo ({error}).',
		unavailable: 'El comprobador de packs no forma parte de esta versión de la web.'
	},
	create: {
		title: 'Crear un pack',
		description:
			'Crea un pack de sonidos de Cucuto en el navegador: imágenes, clips grabados o importados, recortados y nivelados, comprobados y descargados como .soundpack.zip. No se sube nada.',
		leadHtml:
			'Elige imágenes, graba o importa sonidos y descarga un <code>.soundpack.zip</code> que la app importa. Usa las mismas reglas que la app, así que lo que hagas aquí se importa en cualquier sitio.',
		privacy: 'Todo pasa en este dispositivo: las imágenes y los sonidos nunca se suben.',
		offline:
			'Funciona sin conexión: instala esta web como app desde el menú del navegador y crea packs sin internet.',
		loading: 'Cargando el creador de packs…',
		unavailable: 'El creador de packs no forma parte de esta versión de la web.',
		loadFailed:
			'No se ha podido cargar el creador de packs ({error}). Recarga la página para reintentarlo.',
		wavNotice:
			'Este navegador no sabe codificar Opus, así que los clips se guardan en WAV. Ocupan más, pero funcionan igual. Chrome, Edge y Firefox en el ordenador guardan Opus.',
		drafts: {
			title: 'Tus borradores',
			intro:
				'El pack que estás haciendo se guarda en este navegador después de cada cambio, también sin conexión. No se sube nada; si borras los datos de la web, se borran los borradores.',
			saved: 'Borrador guardado en este dispositivo',
			saving: 'Guardando el borrador…',
			unavailable:
				'Este navegador no puede guardar borradores (una ventana privada, o los datos de la web están bloqueados). Descarga el pack antes de salir de la página.',
			saveFailed: 'No se ha podido guardar el borrador ({error}).',
			loadFailed: 'No se ha podido abrir este borrador ({error}).',
			untitled: 'Pack sin nombre',
			summary: 'Personajes: {characters} · clips: {clips} · {date}',
			current: 'Abierto ahora',
			resume: 'Seguir',
			delete: 'Borrar',
			confirmDelete: '¿Borrar «{name}»? No se puede deshacer.',
			confirm: 'Borrar',
			cancel: 'Conservarlo',
			new: 'Empezar un pack nuevo',
			open: 'Abrir un archivo de pack',
			openHint: 'Edita un .soundpack.zip hecho aquí, en la app o con packer.'
		},
		opening: {
			busy: 'Abriendo {name}…',
			invalid: 'No se puede abrir {name}: no pasa la comprobación de packs.',
			bundle: '{name} es un paquete de varios packs. Elige el que quieres editar:',
			failed: 'No se ha podido abrir {name} ({error}).',
			editing: 'Editando {name} {version}',
			update:
				'Se guarda como actualización: el mismo ID de pack, versión {version}. La app sustituye el pack instalado y conserva la voz de cada contacto.',
			copy: 'Guardarlo como un pack nuevo, con su propio ID y la versión 1.0.0, para instalarlo junto al original',
			kept: 'Las imágenes animadas se conservan tal cual del original, aunque esta página aún no las edita: {animations}.',
			undecodable:
				'Clips que este navegador no puede reproducir: {n}. Se quedan en el pack tal cual, sin vista previa ni edición.'
		},
		details: {
			title: 'Datos del pack',
			name: 'Nombre del pack',
			author: 'Autor',
			authorHint: 'Tu nombre o apodo, se muestra en la app.',
			license: 'Licencia',
			licenses: {
				'Personal use only':
					'Solo uso personal: lo adecuado para clips e imágenes que no has hecho tú',
				'CC0-1.0': 'CC0 1.0: cualquiera puede usarlo, sin condiciones',
				'CC-BY-4.0': 'CC BY 4.0: cualquiera puede usarlo, citándote',
				other: 'Otra licencia'
			},
			licenseOther: 'Tu licencia',
			licenseOtherHintHtml:
				'Un <a href="https://spdx.org/licenses/" rel="external">identificador SPDX</a> como <code>MIT</code>, o tus propias palabras, como «Solo uso personal».',
			descriptionLabel: 'Descripción (opcional)',
			packId: 'ID del pack',
			packIdHint: 'Se forma con el autor y el nombre; no cambia en este borrador.',
			packIdKept:
				'El ID del pack que has abierto, para que la app lo trate como una actualización.',
			version: 'Versión',
			versionHint: 'Súbela en cada actualización que compartas, por ejemplo 1.0.1 o 1.1.0.'
		},
		cover: {
			title: 'Portada (opcional)',
			hint: 'Se muestra en la tarjeta del pack en la app. Sin portada, la app muestra el primer personaje.',
			choose: 'Elegir una portada',
			change: 'Cambiar portada',
			remove: 'Quitar portada',
			alt: 'Portada de {name}',
			none: 'Sin portada'
		},
		characters: {
			title: 'Personajes',
			intro:
				'Cada personaje es una imagen con clips para los mensajes, tonos para las llamadas o ambos. La app elige al azar uno de los clips del personaje para cada mensaje.',
			add: 'Añadir un personaje',
			empty: 'Todavía no hay personajes.',
			heading: 'Personaje {n}',
			name: 'Nombre',
			remove: 'Quitar personaje',
			color: 'Color',
			colorHint:
				'Sugerido a partir de la imagen. La app usa este color para el tema del personaje.',
			description: 'Descripción (opcional)'
		},
		picture: {
			choose: 'Elegir una imagen',
			change: 'Cambiar imagen',
			alt: 'Imagen de {name}',
			none: 'Todavía sin imagen',
			failed: 'No se ha podido abrir esta imagen ({error}).'
		},
		crop: {
			title: 'Encuadra la imagen',
			help: 'Arrastra la imagen o usa las flechas del teclado para moverla. Haz zoom con el control deslizante, la rueda del ratón o las teclas + y −.',
			area: 'Zona de encuadre de la imagen',
			zoom: 'Zoom',
			use: 'Usar imagen',
			cancel: 'Cancelar'
		},
		clips: {
			title: 'Clips',
			record: 'Grabar',
			stop: 'Parar la grabación',
			recording: 'Grabando: {time} s de {max} s',
			upload: 'Añadir archivos de audio',
			play: 'Reproducir',
			stopPlaying: 'Parar',
			edit: 'Editar',
			remove: 'Quitar',
			label: 'Etiqueta',
			weight: 'Peso',
			weightHint: 'Con qué frecuencia suena comparado con los otros clips (1 es lo normal).',
			seconds: '{s} s',
			longHint:
				'Dura más de 5 s. Android reproduce los sonidos de notificación hasta el final y baja el resto del audio mientras tanto: bien para una sintonía, pero los clips cortos son más ágiles.',
			micFailed:
				'El micrófono no está disponible ({error}). Revisa el permiso de micrófono de la web.',
			noMic: 'Este navegador no puede grabar. Añade archivos de audio.',
			decodeFailed: 'No se ha podido abrir {name}: este navegador no sabe reproducirlo.',
			tooLong: '{name} dura más de 6 minutos, el audio más largo que abre el creador.',
			recordingLabel: 'Grabación {n}'
		},
		conditions: {
			clipTitle: 'Cuándo suena',
			ringtoneTitle: 'Cuándo suena',
			intro: 'Déjalo todo como está para que el clip pueda sonar con cualquier mensaje.',
			ringtoneIntro:
				'La app hace sonar la versión más específica cuya condición se cumple, y la primera cuando no se cumple ninguna.',
			moment: 'Qué mensaje',
			momentAny: 'Cualquier mensaje, en la rotación',
			momentFirst: 'El primer mensaje del día del chat (los días empiezan a las 4:00)',
			momentBurst: 'Un mensaje seguido, cada uno a menos de un minuto del anterior',
			burst: 'Número de mensaje',
			rare: 'Clip raro: fuera de la rotación, suena de vez en cuando',
			oneIn: 'Más o menos un mensaje de cada',
			time: 'Momento del día',
			timeAny: 'A cualquier hora',
			timeDay: 'De día',
			timeNight: 'De noche',
			timeHours: 'Entre dos horas',
			dayHint:
				'El día va del amanecer al anochecer en el lugar configurado en la app (de 7:00 a 19:00 hasta que configures uno).',
			from: 'Desde',
			to: 'Hasta',
			hoursHint: 'Incluye «desde», excluye «hasta» y puede pasar de medianoche (22:00 a 06:00).',
			seasons: 'Estaciones',
			seasonsHint: 'Sin elegir: todo el año. Se invierten al sur del ecuador.',
			weekdays: 'Días de la semana',
			weekdaysHint: 'Sin elegir: todos los días.',
			dates: 'Fechas',
			datesHint:
				'Días del año, cada año, como mes-día (12-25). Añade un día final para un intervalo; puede pasar de año.',
			dateFrom: 'Día',
			dateTo: 'Hasta (opcional)',
			date: 'Fecha {n}',
			addDate: 'Añadir una fecha',
			removeDate: 'Quitar la fecha {n}',
			save: 'Guardar',
			cancel: 'Cancelar',
			problems: {
				TIME_INVALID: 'Escribe las horas como HH:MM, por ejemplo 22:00.',
				TIME_RANGE_EMPTY: 'Las horas empiezan y acaban a la vez. Elige otro final.',
				DATE_INVALID:
					'La fecha {n} no es un día del año. Escríbela como mes-día, por ejemplo 12-25.',
				TOO_MANY_DATES: 'Una condición puede tener hasta 50 fechas.',
				BURST_OUT_OF_RANGE: 'El número de mensaje va de 2 a 50.',
				ONE_IN_OUT_OF_RANGE: 'Un clip raro suena más o menos en un mensaje de cada 2 a 10.000.',
				RINGTONE_DURATION_OUT_OF_RANGE: 'Los tonos de llamada duran de 5 segundos a 6 minutos.',
				LOOP_INVALID:
					'El bucle debe durar al menos 1 segundo y acabar antes que la canción. Mueve su inicio o su final.',
				FADE_IN_OUT_OF_RANGE: 'La entrada gradual dura de 0 a 10 segundos.',
				WEIGHT_OUT_OF_RANGE: 'La frecuencia va de 0,1 a 100.'
			} as Record<string, string>
		},
		summary: {
			day: 'De día',
			night: 'De noche',
			hours: 'De {from} a {to}',
			firstOfDay: 'Primer mensaje del día',
			burst: 'Mensaje {n} seguido',
			rare: 'Raro: más o menos 1 de cada {n}',
			dateRange: 'Del {from} al {to}',
			always: 'Siempre',
			seasons: {
				spring: 'Primavera',
				summer: 'Verano',
				autumn: 'Otoño',
				winter: 'Invierno'
			} as Record<string, string>
		},
		ringtones: {
			title: 'Tonos de llamada',
			intro:
				'Canciones para las llamadas de los contactos que eligen este personaje como tono. Varias versiones de una canción pueden sonar en momentos distintos, como de día y de noche. Un personaje puede tener solo tonos de llamada.',
			add: 'Añadir un tono de llamada',
			hint: 'Archivos de audio de 5 segundos a 6 minutos, que se guardan en su propio formato.',
			play: 'Reproducir',
			stopPlaying: 'Parar',
			label: 'Etiqueta',
			edit: 'Editar',
			addVersion: 'Añadir una versión',
			remove: 'Quitar',
			versionLabel: '{label} (versión {n})',
			untitled: 'Tono {n}',
			tooShort: '{name} dura {s} s. Los tonos de llamada necesitan al menos 5 segundos.',
			tooLong: '{name} dura más de 6 minutos, lo máximo para un tono de llamada.',
			unsupported:
				'{name} no está en un formato que acepten los packs. Usa Ogg, Opus, MP3, M4A, AAC, FLAC o WAV.',
			decodeFailed: 'No se ha podido abrir {name}: este navegador no puede reproducirlo.',
			noPreview: 'Este navegador no puede reproducir este archivo; se queda en el pack tal cual.'
		},
		ringtoneEditor: {
			title: 'Editar tono de llamada',
			source: '{name}, {s} s',
			label: 'Etiqueta',
			loop: 'Repetición',
			loopWhole: 'Repetir toda la canción',
			loopOnce: 'Sonar una vez y luego silencio',
			loopSection: 'Sonar una intro una vez y luego repetir una parte',
			loopStart: 'El bucle empieza',
			loopEnd: 'El bucle acaba',
			playLoop: 'Reproducir el bucle',
			fadeIn: 'Entrada gradual',
			fadeInValue: '{s} s',
			weight: 'Frecuencia en «aleatorio en cada llamada»',
			weightHint:
				'Comparada con las otras versiones, cuando un contacto elige una versión al azar en cada llamada (1 es lo normal).',
			save: 'Guardar',
			cancel: 'Cancelar'
		},
		more: {
			title: 'Más datos (opcional)',
			homepage: 'Página web',
			homepageHint: 'Una página sobre el pack, que empiece por https://.',
			homepageInvalid: 'Escribe una dirección completa que empiece por https://.',
			tags: 'Etiquetas',
			tagsHint: 'Palabras separadas por comas, por ejemplo: animales, 8-bit.',
			tagsResult: 'Se guardan como: {tags}'
		},
		clipEditor: {
			title: 'Editar clip',
			source: 'De {name}, {s} s',
			start: 'Inicio',
			end: 'Fin',
			selection: 'Seleccionado: {s} s',
			hook: 'Buscar el estribillo',
			hookHint: 'Selecciona los {s} s más fuertes, normalmente el estribillo.',
			trimSilence: 'Recortar el silencio de los extremos',
			normalize: 'Nivelar el volumen, como hace packer',
			play: 'Reproducir la selección',
			save: 'Guardar clip',
			cancel: 'Cancelar',
			processing: 'Procesando…',
			tooShort: 'Tras recortarlo, este clip dura {s} s. Los clips necesitan al menos 0,2 s.',
			tooLong: 'Los clips pueden durar hasta 30 s. Acorta la selección.',
			silent: 'Esta parte está en silencio. Elige otra parte o vuelve a grabar.',
			failed: 'No se ha podido guardar el clip ({error}).'
		},
		haptics: {
			title: 'Vibración',
			text: 'Cada personaje vibra al ritmo de su voz: la app calcula el patrón a partir de cada clip al importar el pack. Para quitarla o diseñar tu propio patrón, usa Vibración en la tarjeta del personaje. Los navegadores no pueden reproducir estas vibraciones: importa el pack en la app para sentirlas.'
		},
		hapticEditor: {
			title: 'Vibración',
			auto: 'Seguir la voz (automática)',
			none: 'Sin vibración',
			custom: 'Patrón personalizado',
			intro:
				'Toques y ondas en una línea de tiempo, en milisegundos desde el inicio del sonido. Los navegadores no pueden reproducirlos: importa el pack en la app y prueba el patrón allí.',
			timeline: 'Línea de tiempo del patrón: {n} eventos en {ms} ms',
			empty: 'Todavía no hay eventos. Añade un toque o una onda, o pega un patrón.',
			emptyNote: 'Hasta que el patrón tenga un evento, el pack usa la vibración automática.',
			tap: 'Toque',
			swell: 'Onda',
			addTap: 'Añadir un toque',
			addSwell: 'Añadir una onda',
			event: 'Evento {n}: {kind}',
			start: 'Inicio (ms)',
			preset: 'Tipo de toque',
			presets: {
				CLICK: 'Clic (nítido)',
				TICK: 'Tic (más suave)',
				LOW_TICK: 'Tic grave (el más suave)'
			},
			strength: 'Fuerza',
			point: 'Punto {n}',
			intensity: 'Intensidad',
			sharpness: 'Nitidez',
			duration: 'Tiempo hasta llegar (ms)',
			addPoint: 'Añadir un punto',
			removePoint: 'Quitar el punto {n}',
			removeEvent: 'Quitar el evento {n}',
			valid: 'Patrón válido: {n} eventos, {ms} ms.',
			copyAi: 'Copiar para IA',
			copyAiHint:
				'Copia un texto con la duración y el volumen de los clips, el patrón actual y el formato. Pégalo en cualquier chat de IA (adjunta el sonido si el chat acepta audio) y luego pega la respuesta abajo. Cucuto nunca envía nada a una IA por su cuenta.',
			copied: 'Copiado. Pégalo en un chat de IA.',
			copyFailed: 'El navegador no ha dejado copiar. Selecciona el texto de abajo y cópialo.',
			paste: 'Pegar un patrón',
			pasteHint: 'JSON de un chat de IA o de otro pack: la vibración entera o solo sus eventos.',
			usePattern: 'Usar el patrón',
			pasted: 'Patrón sustituido. Deshacer recupera el anterior.',
			undo: 'Deshacer',
			where: {
				pattern: 'El patrón',
				event: 'Evento {event}',
				point: 'Evento {event}, punto {point}'
			},
			problems: {
				NotJson: 'No hay ningún patrón JSON en el texto.',
				NotCustom: 'El modo es «{value}»; un patrón pegado tiene que ser «custom».',
				NoEvents: 'El patrón no tiene eventos.',
				TooManyEvents: 'Un patrón puede tener hasta 64 eventos.',
				NotAnEvent: 'El evento {event} necesita «preset» o «envelope» (solo uno de los dos).',
				MissingField: '{where}: falta «{field}».',
				UnknownField: '{where}: «{field}» no forma parte del formato.',
				NotWholeNumber:
					'{where}: «{field}» tiene que ser un número entero de milisegundos, no {value}.',
				OutOfRange: '{where}: «{field}» tiene que estar entre {min} y {max}, no {value}.',
				UnknownPreset: 'Evento {event}: «{value}» no es un tipo de toque (CLICK, TICK o LOW_TICK).',
				PointCount: 'Evento {event}: una onda necesita de 1 a 16 puntos, no {count}.',
				NotClosed: 'Evento {event}: el último punto de una onda tiene que tener intensidad 0.',
				Unsorted:
					'El evento {event} empieza antes que el de arriba. Ordena los eventos por inicio.',
				Overlap:
					'El evento {event} empieza a los {ms} ms, antes de que acabe la onda de arriba a los {until} ms.',
				TooLong: 'El patrón dura {ms} ms; tiene que acabar antes de los 5000 ms.'
			}
		},
		build: {
			title: 'Comprobar y descargar',
			intro:
				'Cucuto comprueba el pack con las mismas reglas que la app antes de que lo descargues.',
			check: 'Comprobar el pack',
			checking: 'Creando y comprobando…',
			incomplete: 'Ya casi está:',
			download: 'Descargar el pack',
			share: 'Enviar a una app',
			shareHint: 'Abre el menú para compartir del móvil, donde puedes elegir Cucuto.',
			installHtml:
				'Para instalarlo, abre el archivo en el móvil con la app Cucuto, o impórtalo desde la pantalla Packs. También puedes <a href="{authors}#host">publicarlo en un repositorio de packs</a>.',
			stale:
				'Has cambiado el pack desde esta comprobación. Vuelve a comprobarlo antes de descargarlo.',
			failed: 'No se ha podido crear el pack ({error}).',
			problems: {
				PACK_NAME_MISSING: 'Ponle un nombre al pack.',
				AUTHOR_MISSING: 'Añade un autor.',
				LICENSE_MISSING: 'Elige una licencia.',
				NO_CHARACTERS: 'Añade al menos un personaje.',
				TOO_MANY_CHARACTERS: 'Un pack puede tener hasta 500 personajes.',
				CHARACTER_NAME_MISSING: 'Ponle nombre al personaje {n}.',
				SPRITE_MISSING: 'Elige una imagen para {name}.',
				NO_CLIPS: 'Añade un clip o un tono de llamada a {name}.',
				TOO_MANY_CLIPS: '{name} tiene más de 200 clips.',
				UNSUPPORTED_FILE: '{name} tiene un archivo que el formato de pack no acepta.'
			}
		},
		tools: {
			title: 'Más herramientas',
			bundle: 'Juntar varios packs',
			bundleText: 'Junta packs terminados en un solo archivo que los instala todos a la vez.',
			repository: 'Aloja tu propio repositorio',
			repositoryText:
				'Crea una carpeta con repository.json, vistas previas y tus packs, lista para subir a GitHub Pages o a cualquier alojamiento web.'
		}
	},
	bundle: {
		title: 'Juntar packs',
		description:
			'Junta varios packs de sonidos de Cucuto en un solo paquete .soundpack.zip en el navegador, como packer bundle. No se sube nada.',
		leadHtml:
			'Un paquete es un solo archivo con varios packs dentro, cada uno con su propia licencia. La app instala todos sus packs a la vez. Esta página crea la misma estructura que <code>packer bundle</code>, en tu dispositivo.',
		back: 'Volver al creador de packs',
		packs: {
			title: 'Packs',
			add: 'Añadir archivos de pack',
			hint: 'Archivos .soundpack.zip hechos aquí, en la app o con packer. Un paquete no puede contener otro paquete.',
			empty: 'Todavía no hay packs.',
			item: '{id} · versión {version} · {characters} personajes',
			remove: 'Quitar {name}',
			invalid: '{name} no pasa la comprobación de packs, así que no puede ir en un paquete.',
			isBundle: '{name} ya es un paquete. Añade los packs que lleva dentro.',
			failed: 'No se ha podido leer {name} ({error}).'
		},
		details: {
			title: 'Datos del paquete',
			name: 'Nombre del paquete',
			author: 'Autor (opcional)',
			id: 'ID del paquete',
			idHint:
				'Se forma con el autor y el nombre. Tiene que ser distinto de los ID de los packs, e igual en cada actualización.',
			version: 'Versión',
			versionHint: 'Súbela en cada actualización que compartas, por ejemplo 1.0.1.',
			license: 'Licencia (opcional)',
			licenseHint: 'Cada pack conserva su propia licencia.',
			description: 'Descripción (opcional)',
			homepage: 'Página web (opcional)',
			homepageHint: 'Una página sobre el paquete, que empiece por https://.',
			homepageInvalid: 'Escribe una dirección completa que empiece por https://.',
			tags: 'Etiquetas (opcional)',
			tagsHint: 'Palabras separadas por comas, por ejemplo: animales, 8-bit.',
			tagsResult: 'Se guardan como: {tags}'
		},
		build: {
			title: 'Comprobar y descargar',
			check: 'Comprobar el paquete',
			checking: 'Creando y comprobando…',
			download: 'Descargar el paquete',
			incomplete: 'Ya casi está:',
			stale:
				'Has cambiado el paquete desde esta comprobación. Vuelve a comprobarlo antes de descargarlo.',
			failed: 'No se ha podido crear el paquete ({error}).',
			problems: {
				BUNDLE_ID_INVALID:
					'El ID del paquete tiene que ser palabras en minúscula separadas por puntos, por ejemplo local.yo.todos-mis-packs.',
				BUNDLE_NAME_MISSING: 'Ponle un nombre al paquete.',
				VERSION_INVALID: 'La versión tiene que tener la forma 1.0.0.',
				NO_PACKS: 'Añade al menos un pack.',
				TOO_MANY_PACKS: 'Un paquete puede llevar hasta 100 packs.',
				PACK_TWICE: 'Dos packs tienen el mismo ID. Deja solo uno.',
				ID_CLASH: 'El ID del paquete es igual que el de uno de sus packs. Elige otro.',
				UNSUPPORTED_FILE: 'La portada no es una imagen que el formato de pack acepte.'
			}
		}
	},
	repository: {
		title: 'Aloja tu propio repositorio',
		description:
			'Crea en el navegador un repositorio de packs de Cucuto listo para subir (repository.json, vistas previas y packs), como packer index. No se sube nada.',
		leadHtml:
			'Un repositorio de packs es una carpeta en cualquier alojamiento HTTPS: un índice <code>repository.json</code>, vistas previas pequeñas y los archivos de pack. La app lo muestra en Descubrir y avisa de las actualizaciones. Esta página crea la misma carpeta que <code>packer index</code>, en tu dispositivo.',
		back: 'Volver al creador de packs',
		files: {
			title: 'Packs y paquetes',
			add: 'Añadir archivos de pack',
			hint: 'Packs o paquetes .soundpack.zip. Si un pack está dos veces, el índice muestra la versión más alta.',
			empty: 'Todavía no hay packs.',
			item: '{kind} · {id} · versión {version} · {size}',
			pack: 'Pack',
			bundle: 'Paquete',
			remove: 'Quitar {name}',
			invalid: '{name} no pasa la comprobación de packs, así que no se puede incluir.',
			failed: 'No se ha podido leer {name} ({error}).'
		},
		details: {
			title: 'Datos del repositorio',
			name: 'Nombre del repositorio',
			description: 'Descripción (opcional)',
			homepage: 'Página web (opcional)',
			homepageHint: 'Una dirección https:// sobre ti o tus packs.',
			where: 'Dónde están los archivos de pack',
			here: 'En la misma carpeta, dentro de packs/ (lo más sencillo)',
			elsewhere: 'En otro sitio, por ejemplo GitHub Releases',
			baseUrl: 'Dirección de la carpeta con los archivos de pack',
			baseUrlHint:
				'Solo https://. Cada pack aparece como esta dirección seguida del nombre de su archivo.',
			httpsOnly: 'Usa una dirección https://.'
		},
		build: {
			title: 'Crear la carpeta',
			make: 'Crear el repositorio',
			making: 'Creando el índice…',
			incomplete: 'Ya casi está:',
			nameMissing: 'Ponle un nombre al repositorio.',
			noFiles: 'Añade al menos un archivo de pack.',
			failed: 'No se ha podido crear el repositorio ({error}).',
			invalid: 'El índice no pasa la comprobación de repositorios:',
			ready:
				'Listo. Entradas en el índice: {n}. Descomprime la carpeta y sube todo lo que lleva dentro.',
			download: 'Descargar la carpeta (.zip)',
			copy: 'Copiar repository.json',
			copied: 'Copiado.',
			stale:
				'Has cambiado algo desde que se creó la carpeta. Vuelve a crearla antes de descargarla.'
		},
		host: {
			title: 'Ponlo en internet',
			githubTitle: 'GitHub Pages (gratis)',
			github: [
				'Crea un repositorio público en GitHub, por ejemplo <code>mis-packs</code>.',
				'Descomprime la carpeta. En la página del repositorio, elige <strong>Add file → Upload files</strong>, suelta todo lo que había dentro de la carpeta (<code>repository.json</code>, <code>previews</code> y <code>packs</code>) y haz commit.',
				'Abre <strong>Settings → Pages</strong>. En <strong>Build and deployment</strong>, pon como origen <strong>Deploy from a branch</strong>, elige <code>main</code> y <code>/ (root)</code>, y guarda.',
				'En un par de minutos el repositorio estará en <code>https://&lt;tu usuario&gt;.github.io/mis-packs/</code>. En la app, abre <strong>Descubrir → Añadir un repositorio</strong> y pega esa dirección.'
			],
			githubNote:
				'La página de subida de GitHub acepta archivos de hasta 25 MB, y Git rechaza los de más de 100 MB. Pon los packs más grandes en GitHub Releases y elige «En otro sitio» con la dirección de descarga de la release.',
			driveTitle: 'Google Drive',
			driveHtml:
				'Drive no puede servir un repositorio: sus enlaces apuntan a ID de archivo, no a rutas junto a <code>repository.json</code>. Comparte los packs uno a uno: sube el <code>.soundpack.zip</code>, compártelo con <strong>Cualquier persona con el enlace</strong> y pega el enlace en <strong>Descubrir → Añadir un repositorio</strong> de la app, que lo descarga directamente. Para una lista de packs con actualizaciones, usa GitHub Pages u otro alojamiento web.',
			otherTitle: 'Otros alojamientos web',
			otherHtml:
				'Cloudflare Pages, Netlify, un bucket de almacenamiento o tu propio servidor funcionan igual: sube lo que hay dentro de la carpeta y comparte su dirección <code>https://</code>. No hace falta código de servidor.',
			address: 'La dirección de tu repositorio (opcional)',
			addressHint:
				'Dónde estará la carpeta en internet, para tener un enlace que la añade a la app de un toque.',
			addLink: 'Enlace para añadirlo a la app:',
			docsHtml:
				'Más información en <a href="{authors}#host">Aloja tu propio repositorio</a> y en el <a href="{packFormat}#pack-repositories">formato de pack</a>.'
		}
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
