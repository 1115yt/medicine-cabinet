# 保留备份格式和录入请求的序列化模型，保证优化构建与旧备份兼容。
-keep class app.medicinecabinet.domain.** { *; }
-keep class app.medicinecabinet.ui.forms.EditorRequest { *; }
-keep class app.medicinecabinet.ui.forms.EditorRequest$$serializer { *; }
