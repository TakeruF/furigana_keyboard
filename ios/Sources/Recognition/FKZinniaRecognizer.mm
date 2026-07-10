#import "FKZinniaRecognizer.h"

#include <algorithm>
#include <memory>
#include "zinnia.h"

static NSString *const FKZinniaErrorDomain = @"FuriganaKeyboard.Zinnia";

@implementation FKZinniaResult

- (instancetype)initWithText:(NSString *)text score:(float)score {
    self = [super init];
    if (self) {
        _text = [text copy];
        _score = score;
    }
    return self;
}

@end

@implementation FKZinniaRecognizer {
    std::unique_ptr<zinnia::Recognizer> _recognizer;
}

- (nullable instancetype)initWithModelPath:(NSString *)modelPath
                                     error:(NSError * _Nullable * _Nullable)error {
    self = [super init];
    if (!self) return nil;

    _recognizer.reset(zinnia::Recognizer::create());
    if (!_recognizer || !_recognizer->open(modelPath.fileSystemRepresentation)) {
        NSString *reason = _recognizer
            ? [NSString stringWithUTF8String:_recognizer->what()]
            : @"Could not allocate the recognizer";
        if (error) {
            *error = [NSError errorWithDomain:FKZinniaErrorDomain
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey: reason ?: @"Unable to load model"}];
        }
        return nil;
    }
    return self;
}

- (NSArray<FKZinniaResult *> *)recognizeStrokes:(NSArray<NSArray<NSValue *> *> *)strokes
                                           width:(NSInteger)width
                                          height:(NSInteger)height
                                           limit:(NSInteger)limit {
    if (!_recognizer || strokes.count == 0) return @[];

    std::unique_ptr<zinnia::Character> character(zinnia::Character::create());
    character->set_width(static_cast<size_t>(std::max<NSInteger>(1, width)));
    character->set_height(static_cast<size_t>(std::max<NSInteger>(1, height)));

    for (NSUInteger strokeIndex = 0; strokeIndex < strokes.count; ++strokeIndex) {
        NSArray<NSValue *> *points = strokes[strokeIndex];
        for (NSValue *value in points) {
            CGPoint point = CGPointZero;
            [value getValue:&point size:sizeof(point)];
            character->add(strokeIndex, static_cast<int>(point.x), static_cast<int>(point.y));
        }
    }

    std::unique_ptr<zinnia::Result> result(
        _recognizer->classify(*character, static_cast<size_t>(std::max<NSInteger>(1, limit))));
    if (!result) return @[];

    NSMutableArray<FKZinniaResult *> *output = [NSMutableArray arrayWithCapacity:result->size()];
    for (size_t index = 0; index < result->size(); ++index) {
        NSString *text = [NSString stringWithUTF8String:result->value(index)];
        if (text) [output addObject:[[FKZinniaResult alloc] initWithText:text score:result->score(index)]];
    }
    return output;
}

@end
