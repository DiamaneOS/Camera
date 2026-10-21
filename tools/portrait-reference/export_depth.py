#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Export the saved-photo Portrait depth model from the original Apache-2.0 weights.

Depth Anything V2 Small at 518x518, RGB in [0, 1] with ImageNet normalization inside the graph,
relative inverse depth out. Builtin LiteRT operators only.

The weights are rounded to float16 and stored as float32. Float16 weights behind dequantize nodes
ran about five times slower on the XNNPACK CPU path in our host test. Float16-valued float32 weights
keep float32 speed and compress to about 58 MB. The output is gzip, unpacked by the app.
Run with the pinned packages in requirements-export.txt (Python 3.12 or 3.13). The result matches
EXPORT_SHA256 on macOS arm64 and Linux x86_64.
"""
import argparse
import gzip
from collections import OrderedDict
import hashlib
import json
import os
from pathlib import Path
import pickle
import tempfile
import urllib.request
from urllib.parse import urlparse
import zipfile

import numpy as np

WEIGHTS = {
    'url': 'https://huggingface.co/depth-anything/Depth-Anything-V2-Small/resolve/'
           '03876f8651c73a60fe4c2c48294e09fcb6838fcf/depth_anything_v2_vits.pth',
    'bytes': 99_218_434,
    'sha256': '715fade13be8f229f8a70cc02066f656f2423a59effd0579197bbf57860e1378',
}
EXPORT_SHA256 = '72ba875d48c520c3ced0cd10782f81fab5764c24c34ee7620e9d3e075a866de5'
SIDE, PATCH, DIM, HEADS, DEPTH = 518, 14, 384, 6, 12
TAKEN = (2, 5, 8, 11)
OUT_CHANNELS = (48, 96, 192, 384)
MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1 << 20), b''):
            value.update(chunk)
    return value.hexdigest()


def fetch(target):
    if urlparse(WEIGHTS['url']).scheme != 'https':
        raise SystemExit('Only the pinned HTTPS source is allowed.')
    with tempfile.NamedTemporaryFile(dir=target.parent, prefix='.weights-', delete=False) as output:
        temporary = Path(output.name)
        try:
            with urllib.request.urlopen(WEIGHTS['url'], timeout=60) as response:
                if urlparse(response.geturl()).scheme != 'https':
                    raise RuntimeError('Non-HTTPS redirect')
                total = 0
                while chunk := response.read(1 << 20):
                    total += len(chunk)
                    if total > WEIGHTS['bytes']:
                        raise RuntimeError('Oversized response')
                    output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
            if digest(temporary) != WEIGHTS['sha256']:
                raise RuntimeError('Weights checksum mismatch')
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)


class StateDict(pickle.Unpickler):
    """Reads a plain float32 PyTorch state dict without running pickled code or importing torch."""

    def __init__(self, archive, prefix):
        self.archive, self.prefix = archive, prefix
        super().__init__(archive.open(prefix + 'data.pkl'))

    def find_class(self, module, name):
        allowed = {('collections', 'OrderedDict'): OrderedDict,
                   ('torch._utils', '_rebuild_tensor_v2'): self.rebuild,
                   ('torch', 'FloatStorage'): np.float32}
        if (module, name) not in allowed:
            raise pickle.UnpicklingError(f'unexpected global {module}.{name}')
        return allowed[(module, name)]

    def persistent_load(self, pid):
        kind, dtype, key, _, count = pid
        if kind != 'storage' or dtype is not np.float32:
            raise pickle.UnpicklingError('unexpected storage')
        data = np.frombuffer(self.archive.read(f'{self.prefix}data/{key}'), '<f4')
        if data.size != count:
            raise pickle.UnpicklingError('storage size mismatch')
        return data

    @staticmethod
    def rebuild(storage, offset, size, stride, *_):
        if min(size, default=1) < 1 or min(stride, default=0) < 0 or \
                offset + sum((n - 1) * s for n, s in zip(size, stride)) >= storage.size:
            raise pickle.UnpicklingError('tensor out of bounds')
        view = np.lib.stride_tricks.as_strided(storage[offset:], tuple(size),
                                               tuple(s * 4 for s in stride), writeable=False)
        return np.array(view, np.float32)


def load_weights(path):
    with zipfile.ZipFile(path) as archive:
        pickles = [n for n in archive.namelist() if n.endswith('/data.pkl')]
        if len(pickles) != 1:
            raise SystemExit('Unexpected checkpoint layout.')
        weights = StateDict(archive, pickles[0][:-len('data.pkl')]).load()
    if not isinstance(weights, dict) or not all(isinstance(v, np.ndarray) for v in weights.values()):
        raise SystemExit('Unexpected checkpoint content.')
    return weights


def build(weights):
    import tensorflow as tf
    used = set()

    def w(name):
        used.add(name)
        return weights[name]

    def const(array):
        # Every weight takes a float16 value. LayerScale is folded first, as the converter would.
        return tf.constant(np.ascontiguousarray(array, np.float32).astype(np.float16).astype(np.float32))

    def linear(x, name, scale=None, norm=None):
        # Fold a preceding LayerNorm's affine and a following LayerScale into the weights.
        tokens = tf.reshape(x, [-1, x.shape[-1]])
        weight, bias = w(name + '.weight'), w(name + '.bias')
        if norm:
            # Float64, so the folded bias rounds the same way with any BLAS.
            bias = (bias + weight.astype(np.float64) @ w(norm + '.bias').astype(np.float64)).astype(np.float32)
            weight = weight * w(norm + '.weight')[None, :]
        if scale:
            weight, bias = weight * w(scale)[:, None], bias * w(scale)
        y = tf.matmul(tokens, const(weight.T)) + const(bias)
        return tf.reshape(y, [1, -1, y.shape[-1]])

    def standardise(x):
        mean = tf.reduce_mean(x, -1, keepdims=True)
        variance = tf.reduce_mean(tf.math.squared_difference(x, mean), -1, keepdims=True)
        return (x - mean) * tf.math.rsqrt(variance + 1e-6)

    def norm(x, name):
        return standardise(x) * const(w(name + '.weight')) + const(w(name + '.bias'))

    def conv(x, name, stride=1, padding=0, bias=True):
        kernel = w(name + '.weight').transpose(2, 3, 1, 0)
        if padding:
            x = tf.pad(x, [[0, 0], [padding, padding], [padding, padding], [0, 0]])
        y = tf.nn.conv2d(x, const(kernel), stride, 'VALID')
        return y + const(w(name + '.bias')) if bias else y

    def upconv(x, name, factor):
        kernel = w(name + '.weight').transpose(2, 3, 1, 0)
        shape = [1, x.shape[1] * factor, x.shape[2] * factor, kernel.shape[2]]
        return tf.nn.conv2d_transpose(x, const(kernel), shape, factor, 'VALID') + const(w(name + '.bias'))

    def resize(x, size):
        return tf.compat.v1.image.resize_bilinear(x, size, align_corners=True, half_pixel_centers=False)

    def attention(x, name, scale, norm_name):
        tokens = x.shape[1]
        qkv = linear(x, name + '.qkv', norm=norm_name)
        q, k, v = (tf.transpose(tf.reshape(qkv[:, :, i * DIM:(i + 1) * DIM], [1, tokens, HEADS, DIM // HEADS]),
                                [0, 2, 1, 3]) for i in range(3))
        scores = tf.nn.softmax(tf.matmul(q * (DIM // HEADS) ** -0.5, k, transpose_b=True), -1)
        out = tf.reshape(tf.transpose(tf.matmul(scores, v), [0, 2, 1, 3]), [1, tokens, DIM])
        return linear(out, name + '.proj', scale)

    def residual(x, name):
        out = conv(tf.nn.relu(x), name + '.conv1', padding=1)
        return conv(tf.nn.relu(out), name + '.conv2', padding=1) + x

    def fusion(name, x, skip=None, size=None):
        if skip is not None:
            x = x + residual(skip, name + '.resConfUnit1')
        x = residual(x, name + '.resConfUnit2')
        x = resize(x, size or [x.shape[1] * 2, x.shape[2] * 2])
        return conv(x, name + '.out_conv')

    @tf.function(input_signature=[tf.TensorSpec([1, SIDE, SIDE, 3], tf.float32, name='image')])
    def model(image):
        x = (image - const(MEAN)) / const(STD)
        grid = SIDE // PATCH
        x = tf.reshape(conv(x, 'pretrained.patch_embed.proj', stride=PATCH), [1, grid * grid, DIM])
        x = tf.concat([const(w('pretrained.cls_token')), x], 1) + const(w('pretrained.pos_embed'))
        taken = []
        for i in range(DEPTH):
            b = f'pretrained.blocks.{i}'
            x = x + attention(standardise(x), b + '.attn', b + '.ls1.gamma', b + '.norm1')
            h = tf.nn.gelu(linear(standardise(x), b + '.mlp.fc1', norm=b + '.norm2'), approximate=False)
            x = x + linear(h, b + '.mlp.fc2', b + '.ls2.gamma')
            if i in TAKEN:
                taken.append(norm(x, 'pretrained.norm')[:, 1:])
        layers = []
        for i, tokens in enumerate(taken):
            y = conv(tf.reshape(tokens, [1, grid, grid, DIM]), f'depth_head.projects.{i}')
            r = f'depth_head.resize_layers.{i}'
            y = upconv(y, r, 4) if i == 0 else upconv(y, r, 2) if i == 1 else y if i == 2 else conv(y, r, 2, 1)
            layers.append(conv(y, f'depth_head.scratch.layer{i + 1}_rn', padding=1, bias=False))
        s = 'depth_head.scratch.'
        path = fusion(s + 'refinenet4', layers[3], size=layers[2].shape[1:3])
        path = fusion(s + 'refinenet3', path, layers[2], layers[1].shape[1:3])
        path = fusion(s + 'refinenet2', path, layers[1], layers[0].shape[1:3])
        path = fusion(s + 'refinenet1', path, layers[0])
        out = resize(conv(path, s + 'output_conv1', padding=1), [grid * PATCH, grid * PATCH])
        out = tf.nn.relu(conv(out, s + 'output_conv2.0', padding=1))
        return tf.nn.relu(conv(out, s + 'output_conv2.2'))

    concrete = model.get_concrete_function()
    unused = sorted(set(weights) - used)
    expected_unused = ['depth_head.scratch.refinenet4.resConfUnit1.conv1.bias',
                       'depth_head.scratch.refinenet4.resConfUnit1.conv1.weight',
                       'depth_head.scratch.refinenet4.resConfUnit1.conv2.bias',
                       'depth_head.scratch.refinenet4.resConfUnit1.conv2.weight', 'pretrained.mask_token']
    if unused != expected_unused:
        raise SystemExit(f'Unexpected weight set: {unused}')
    return model, concrete


def probe_image():
    # A deterministic test pattern: smooth gradients with a nearer central block.
    y, x = np.mgrid[0:SIDE, 0:SIDE].astype(np.float32) / (SIDE - 1)
    image = np.stack([x, y, 0.5 + 0.4 * np.sin(6 * x) * np.cos(4 * y)], -1)
    image[150:370, 180:340] = (0.85, 0.3, 0.2)
    return image[None].astype(np.float32)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path, help='The .tflite.gzip to write. It must not exist yet.')
    parser.add_argument('--weights', type=Path, required=True,
                        help='depth_anything_v2_vits.pth. --fetch downloads it from the pinned source.')
    parser.add_argument('--fetch', action='store_true', help='Download the pinned weights if missing.')
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Output exists and was not overwritten.')
    if not args.weights.exists():
        if not args.fetch:
            raise SystemExit('Weights missing. Run with --fetch to download the pinned Apache-2.0 checkpoint.')
        fetch(args.weights)
    if args.weights.stat().st_size != WEIGHTS['bytes'] or digest(args.weights) != WEIGHTS['sha256']:
        raise SystemExit('Weights checksum mismatch.')

    import tensorflow as tf
    tf.config.set_visible_devices([], 'GPU')
    model, concrete = build(load_weights(args.weights))
    converter = tf.lite.TFLiteConverter.from_concrete_functions([concrete], model)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    flatbuffer = converter.convert()

    interpreter = tf.lite.Interpreter(model_content=flatbuffer, num_threads=2)
    interpreter.allocate_tensors()
    (inp,), (out,) = interpreter.get_input_details(), interpreter.get_output_details()
    if list(inp['shape']) != [1, SIDE, SIDE, 3] or list(out['shape']) != [1, SIDE, SIDE, 1] \
            or inp['dtype'] != np.float32 or out['dtype'] != np.float32:
        raise SystemExit('Unexpected tensors.')
    from tensorflow.lite.python import schema_py_generated as schema
    from tensorflow.lite.tools import flatbuffer_utils
    graph = flatbuffer_utils.convert_bytearray_to_object(bytearray(flatbuffer))
    for tensor in graph.subgraphs[0].tensors:
        data = graph.buffers[tensor.buffer].data
        if tensor.type == schema.TensorType.FLOAT32 and data is not None and len(data) >= 16384:
            value = np.frombuffer(bytes(data), '<f4')
            if not np.array_equal(value, value.astype(np.float16).astype(np.float32)):
                raise SystemExit(f'Weights changed in conversion: {tensor.name.decode()}')
    image = probe_image()
    reference = model(tf.constant(image)).numpy()
    interpreter.set_tensor(inp['index'], image)
    interpreter.invoke()
    lite = interpreter.get_tensor(out['index'])
    error = float(np.sqrt(np.mean((lite - reference) ** 2)) / (np.ptp(reference) + 1e-9))
    if not np.isfinite(lite).all() or error > 0.0005:
        raise SystemExit(f'Converted model differs from the float32 graph: {error}')

    packed = gzip.compress(flatbuffer, 9, mtime=0)
    if gzip.decompress(packed) != flatbuffer:
        raise SystemExit('Compression check failed.')
    args.output.write_bytes(packed)
    print(json.dumps({'file': args.output.name, 'bytes': len(flatbuffer),
                      'sha256': hashlib.sha256(flatbuffer).hexdigest(),
                      'gzipBytes': len(packed), 'gzipSha256': hashlib.sha256(packed).hexdigest(),
                      'weightsSha256': WEIGHTS['sha256'], 'tensorflow': tf.__version__,
                      'probeRelativeRmse': error,
                      'matchesPin': hashlib.sha256(flatbuffer).hexdigest() == EXPORT_SHA256}, indent=2))


if __name__ == '__main__':
    main()
