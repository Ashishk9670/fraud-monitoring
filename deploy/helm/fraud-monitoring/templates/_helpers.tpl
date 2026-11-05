{{- define "fraud-monitoring.name" -}}fraud-monitoring{{- end -}}
{{- define "fraud-monitoring.fullname" -}}{{ .Release.Name }}-{{ include "fraud-monitoring.name" . }}{{- end -}}
{{- define "fraud-monitoring.labels" -}}
app.kubernetes.io/name: {{ include "fraud-monitoring.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
{{- define "fraud-monitoring.selectorLabels" -}}
app.kubernetes.io/name: {{ include "fraud-monitoring.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
