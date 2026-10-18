# Documentación de CamRevert

| Documento | Para quién | Contenido |
|---|---|---|
| [`../README.md`](../README.md) | Todos | Qué es, con qué cámaras funciona, instalación, uso, problemas frecuentes, créditos y licencia. |
| [`CONFIGURACION.md`](CONFIGURACION.md) | Quien va a instalar la app | **Todas las claves explicadas** (qué es `TUCORREO`, `PASSWORD`, `CAMPASSWORD`, `VUID`…), `secrets.properties` vs `.env` vs diálogo de Configuración, y cómo obtener el ID P2P y la dirección `DAS-`. |
| [`COMO-FUNCIONA.md`](COMO-FUNCIONA.md) | Quien quiere entender el proyecto | Arquitectura (RTSP + túnel P2P), protocolos, la autorización de sesión, la tabla de comandos y **el método de ingeniería inversa**: qué herramientas se usaron, qué se leyó (binarios, logs, capturas) y qué se probó. |
| [`HALLAZGOS-PTZ.md`](HALLAZGOS-PTZ.md) | Quien depura el PTZ | El cuaderno de la investigación: el síntoma, la técnica de interceptación de la app oficial, la causa raíz y su prueba en hardware real. |
| [`../AGENTS.md`](../AGENTS.md) | Desarrollo | Especificación técnica interna: direcciones de memoria, desensamblados del SDK, tablas de errores y decisiones de diseño. |

## Atajos

- **"Se ve el vídeo pero el PTZ no responde"** → falta la cuenta Eye4:
  [`CONFIGURACION.md` §3](CONFIGURACION.md#3-por-qué-hacen-falta-tucorreo--password).
- **"No sé de dónde sacar el ID P2P o la dirección `DAS-`"** →
  [`CONFIGURACION.md` §4](CONFIGURACION.md#4-cómo-obtener-el-id-p2p-y-la-dirección-das-).
- **"¿Por qué reutiliza binarios del fabricante?"** →
  [`COMO-FUNCIONA.md` §2](COMO-FUNCIONA.md#2-el-sdk-nativo-por-qué-reutilizamos-binarios-del-fabricante).
- **"¿Qué es `eye4_authentication.cgi`?"** →
  [`COMO-FUNCIONA.md` §5](COMO-FUNCIONA.md#5-la-autorización-de-sesión-eye4_authenticationcgi).
