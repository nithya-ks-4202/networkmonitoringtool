{{/*
Service account name, honouring an externally managed one.
*/}}
{{- define "nms.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (printf "%s-nms" .Release.Name) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Labels shared by every object in the release.
*/}}
{{- define "nms.labels" -}}
app.kubernetes.io/name: nms
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}
